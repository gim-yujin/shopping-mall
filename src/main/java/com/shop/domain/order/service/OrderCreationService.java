package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.event.ProductStockChangedEvent;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.PaymentMethod;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.InsufficientStockException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 주문 생성 전담 서비스.
 *
 * OrderService(God Class)에서 분리: 장바구니 → 주문 변환, 재고 차감,
 * 쿠폰/포인트 처리, 결제 금액 계산 등 주문 생성에 필요한 모든 로직을 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class OrderCreationService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserTierRepository userTierRepository;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final ShippingFeeCalculator shippingFeeCalculator;

    public OrderCreationService(OrderRepository orderRepository, CartRepository cartRepository,
                                ProductRepository productRepository, UserRepository userRepository,
                                ProductInventoryHistoryRepository inventoryHistoryRepository,
                                UserCouponRepository userCouponRepository,
                                UserTierRepository userTierRepository,
                                EntityManager entityManager,
                                ApplicationEventPublisher eventPublisher,
                                ShippingFeeCalculator shippingFeeCalculator) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.userTierRepository = userTierRepository;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.shippingFeeCalculator = shippingFeeCalculator;
    }

    @Transactional
    public Order createOrder(Long userId, OrderCreateRequest request) {
        PaymentMethod paymentMethod = PaymentMethod.fromCode(request.paymentMethod())
                .orElseThrow(() -> new BusinessException("UNSUPPORTED_PAYMENT_METHOD", "지원하지 않는 결제수단"));

        // 같은 사용자의 동시 주문 요청을 트랜잭션 단위로 직렬화
        cartRepository.acquireUserCartLock(userId);

        // [P1-6] 장바구니 선택 주문 지원.
        // cartItemIds가 null/빈 리스트이면 전체 장바구니를 주문한다 (기존 동작 호환).
        // 값이 있으면 해당 ID의 장바구니 항목만 주문 대상으로 사용한다.
        List<Cart> cartItems;
        boolean isPartialOrder;
        if (request.cartItemIds() != null && !request.cartItemIds().isEmpty()) {
            Set<Long> requestedCartItemIds = new LinkedHashSet<>(request.cartItemIds());
            cartItems = cartRepository.findByUserIdAndCartIdIn(userId, new ArrayList<>(requestedCartItemIds));
            Set<Long> foundCartItemIds = cartItems.stream()
                    .map(Cart::getCartId)
                    .collect(java.util.stream.Collectors.toSet());

            if (!requestedCartItemIds.equals(foundCartItemIds)) {
                throw new BusinessException(
                        "INVALID_CART_SELECTION",
                        "유효하지 않거나 접근 불가한 장바구니 항목이 포함됨"
                );
            }
            isPartialOrder = true;
        } else {
            cartItems = cartRepository.findByUserIdWithProduct(userId);
            isPartialOrder = false;
        }
        if (cartItems.isEmpty()) {
            throw new BusinessException("EMPTY_CART", "장바구니가 비어있습니다.");
        }
        // 데드락 예방을 위해 상품 ID 순으로 정렬 (자원 획득 순서 일관성 유지)
        cartItems.sort(java.util.Comparator.comparing(cart -> cart.getProduct().getProductId()));

        // 0) 사용자 & 등급 정보 로드
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        UserTier tier = user.getTier();
        BigDecimal tierDiscountRate = tier.getDiscountRate();  // e.g. 5.00 = 5%

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal tierDiscountTotal = BigDecimal.ZERO;
        String orderNumber = generateOrderNumber();
        List<OrderLine> orderLines = new ArrayList<>();

        // [BUG FIX] 재고 이력을 Order save 이후에 저장하기 위해 임시 보관하는 리스트.
        // 기존 코드는 이 루프 안에서 inventoryHistoryRepository.save()를 호출했는데,
        // 이 시점에는 아직 Order가 persist되지 않아 reference_id(orderId)에 null이 전달되었다.
        // 주문 취소 시에는 orderId가 정상 전달되므로, 생성 쪽과 취소 쪽의 이력 일관성이 깨졌다.
        // 수정: 재고 차감은 즉시 수행하되, 이력 데이터는 InventorySnapshot으로 모아두고
        // Order가 저장된 후에 orderId를 포함하여 일괄 저장한다.
        List<InventorySnapshot> inventorySnapshots = new ArrayList<>();

        // 1) 재고 차감 & 주문 금액 계산
        for (Cart cart : cartItems) {
            Product product = productRepository.findByIdWithLock(cart.getProduct().getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("상품", cart.getProduct().getProductId()));

            // JOIN FETCH로 L1 캐시에 로드된 낡은 상태를 DB 최신값으로 갱신
            // PESSIMISTIC_WRITE 락을 잡은 상태이므로 다른 트랜잭션이 변경할 수 없음
            entityManager.refresh(product);

            if (product.getStockQuantity() < cart.getQuantity()) {
                throw new InsufficientStockException(product.getProductName(),
                        cart.getQuantity(), product.getStockQuantity());
            }

            int beforeStock = product.getStockQuantity();
            product.decreaseStock(cart.getQuantity());

            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

            orderLines.add(new OrderLine(
                    product.getProductId(),
                    product.getProductName(),
                    cart.getQuantity(),
                    product.getPrice(),
                    subtotal
            ));

            // 등급 할인 계산 (아이템별)
            BigDecimal itemTierDiscount = subtotal.multiply(tierDiscountRate)
                    .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
            tierDiscountTotal = tierDiscountTotal.add(itemTierDiscount);

            // 재고 이력 데이터를 임시 보관 (orderId는 Order save 후 설정)
            inventorySnapshots.add(new InventorySnapshot(
                    product.getProductId(), cart.getQuantity(), beforeStock, product.getStockQuantity()));
        }

        // 2) 쿠폰 할인 적용 (상품 금액 기준)
        BigDecimal couponDiscount = BigDecimal.ZERO;
        UserCoupon userCoupon = null;
        if (request.userCouponId() != null) {
            userCoupon = userCouponRepository.findByIdWithLock(request.userCouponId())
                    .orElseThrow(() -> new BusinessException("COUPON_NOT_FOUND", "쿠폰을 찾을 수 없습니다."));

            if (!userCoupon.getUserId().equals(userId)) {
                throw new BusinessException("COUPON_INVALID", "본인의 쿠폰만 사용할 수 있습니다.");
            }
            if (!userCoupon.isAvailable()) {
                throw new BusinessException("COUPON_EXPIRED", "사용할 수 없는 쿠폰입니다.");
            }

            // 쿠폰 최소 주문 기준은 "상품 금액(등급 할인/쿠폰 할인 전)" 기준으로 적용한다.
            couponDiscount = userCoupon.getCoupon().calculateDiscount(totalAmount);
        }

        BigDecimal totalDiscount = tierDiscountTotal.add(couponDiscount);

        // 3) 포인트 사용 (1P = 1원)
        int usePoints = request.usePoints();
        if (usePoints > 0) {
            if (usePoints > user.getPointBalance()) {
                throw new BusinessException("INSUFFICIENT_POINTS",
                        "보유 포인트가 부족합니다. (보유: " + user.getPointBalance() + "P, 요청: " + usePoints + "P)");
            }
            // 포인트 사용 상한: 상품금액 - 할인 (배송비 제외, 최종금액이 0 미만이 되지 않도록)
            BigDecimal maxUsable = totalAmount.subtract(totalDiscount);
            if (maxUsable.compareTo(BigDecimal.ZERO) < 0) {
                maxUsable = BigDecimal.ZERO;
            }
            if (BigDecimal.valueOf(usePoints).compareTo(maxUsable) > 0) {
                usePoints = maxUsable.intValue();
            }
            user.usePoints(usePoints);
        }
        BigDecimal usedPointsAmount = BigDecimal.valueOf(usePoints);

        // 4) 배송비 계산 (등급별 무료배송 기준)
        BigDecimal shippingFee = shippingFeeCalculator.calculateShippingFee(tier, totalAmount);

        // 5) 최종 금액 & 주문 생성
        BigDecimal finalAmount = shippingFeeCalculator.calculateFinalAmount(totalAmount, totalDiscount.add(usedPointsAmount), shippingFee);

        BigDecimal pointRateSnapshot = tier.getPointEarnRate(); // e.g. 1.50 = 1.5%
        int earnedPointsSnapshot = finalAmount.multiply(pointRateSnapshot)
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR).intValue();

        // [P2-11] 등급 할인과 쿠폰 할인을 분리하여 저장
        Order order = new Order(orderNumber, userId, totalAmount, totalDiscount,
                tierDiscountTotal, couponDiscount,
                shippingFee, finalAmount, pointRateSnapshot, earnedPointsSnapshot,
                usePoints,
                paymentMethod.getCode(), request.shippingAddress(),
                request.recipientName(), request.recipientPhone());

        for (OrderLine orderLine : orderLines) {
            OrderItem item = new OrderItem(orderLine.productId(), orderLine.productName(),
                    orderLine.quantity(), orderLine.unitPrice(), tierDiscountRate, orderLine.subtotal());
            order.addItem(item);
        }

        order.markPaid();
        Order savedOrder = orderRepository.save(order);

        // [BUG FIX] 재고 이력에 orderId를 포함하여 저장.
        // 기존: Order save 전에 inventoryHistory를 저장 → reference_id = null
        // 수정: Order save 후 savedOrder.getOrderId()로 정확한 주문 ID를 기록.
        // 이로써 주문 생성(OUT)과 취소(IN) 이력 모두 reference_id가 일관되게 채워진다.
        for (InventorySnapshot snapshot : inventorySnapshots) {
            inventoryHistoryRepository.save(new ProductInventoryHistory(
                    snapshot.productId(), "OUT", snapshot.quantity(),
                    snapshot.beforeStock(), snapshot.afterStock(),
                    "ORDER", savedOrder.getOrderId(), userId
            ));
        }

        // 5) 쿠폰 사용 처리 (DB 레벨 원자적 전환 보장)
        if (userCoupon != null) {
            int updatedRows = userCouponRepository.markAsUsedIfUnused(
                    userCoupon.getUserCouponId(),
                    savedOrder.getOrderId(),
                    LocalDateTime.now()
            );
            if (updatedRows != 1) {
                throw new BusinessException("COUPON_ALREADY_USED", "이미 사용된 쿠폰입니다.");
            }
        }

        // 6) 누적 구매 금액(total_spent) 및 포인트 반영
        // total_spent는 연간 실적이 아니라 취소 반영 누적 금액으로 유지한다.
        user.addTotalSpent(finalAmount);
        user.addPoints(earnedPointsSnapshot);

        // 7) 등급 재계산 (누적 구매 금액 기준)
        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        // [P1-6] 선택 주문인 경우 주문한 장바구니 항목만 삭제, 나머지는 유지한다.
        // 전체 주문인 경우 기존과 동일하게 장바구니를 통째로 삭제한다.
        if (isPartialOrder) {
            List<Long> orderedCartIds = cartItems.stream().map(Cart::getCartId).toList();
            cartRepository.deleteAllById(orderedCartIds);
        } else {
            cartRepository.deleteByUserId(userId);
        }

        // 8) 재고 변경 이벤트 발행 (캐시 무효화는 AFTER_COMMIT 리스너에서 처리)
        eventPublisher.publishEvent(new ProductStockChangedEvent(
                orderLines.stream().map(OrderLine::productId).toList()
        ));

        return savedOrder;
    }

    private String generateOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return datePart + "-" + randomPart;
    }

    // 주문 생성 중 계산된 상품별 스냅샷 데이터를 임시로 보관하는 내부 DTO
    private record OrderLine(Long productId, String productName, int quantity,
                             BigDecimal unitPrice, BigDecimal subtotal) {
    }

    /**
     * [BUG FIX] 재고 차감 시점의 before/after 수량을 임시 보관하는 내부 DTO.
     *
     * 기존 코드는 재고 차감 루프 안에서 즉시 inventoryHistoryRepository.save()를 호출했으나,
     * 이 시점에는 Order가 아직 persist되지 않아 reference_id(orderId)에 null이 전달되었다.
     * (주문 취소 쪽은 이미 존재하는 orderId를 정상 전달하므로 생성/취소 간 이력 일관성이 깨짐)
     *
     * 수정: 재고 차감은 즉시 수행하되(비관적 잠금 구간 내), 이력 데이터는 이 DTO로 모아두고
     * Order가 저장된 후에 savedOrder.getOrderId()를 포함하여 일괄 저장한다.
     */
    private record InventorySnapshot(Long productId, int quantity, int beforeStock, int afterStock) {
    }
}

package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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
    private final CacheManager cacheManager;
    private final ShippingFeeCalculator shippingFeeCalculator;

    public OrderCreationService(OrderRepository orderRepository, CartRepository cartRepository,
                                ProductRepository productRepository, UserRepository userRepository,
                                ProductInventoryHistoryRepository inventoryHistoryRepository,
                                UserCouponRepository userCouponRepository,
                                UserTierRepository userTierRepository,
                                EntityManager entityManager,
                                CacheManager cacheManager,
                                ShippingFeeCalculator shippingFeeCalculator) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.userTierRepository = userTierRepository;
        this.entityManager = entityManager;
        this.cacheManager = cacheManager;
        this.shippingFeeCalculator = shippingFeeCalculator;
    }

    @Transactional
    public Order createOrder(Long userId, OrderCreateRequest request) {
        PaymentMethod paymentMethod = PaymentMethod.fromCode(request.paymentMethod())
                .orElseThrow(() -> new BusinessException("UNSUPPORTED_PAYMENT_METHOD", "지원하지 않는 결제수단"));

        // 같은 사용자의 동시 주문 요청을 트랜잭션 단위로 직렬화
        cartRepository.acquireUserCartLock(userId);

        List<Cart> cartItems = cartRepository.findByUserIdWithProduct(userId);
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

            inventoryHistoryRepository.save(new ProductInventoryHistory(
                    product.getProductId(), "OUT", cart.getQuantity(),
                    beforeStock, product.getStockQuantity(),
                    "ORDER", null, userId
            ));
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

        Order order = new Order(orderNumber, userId, totalAmount, totalDiscount,
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

        // 6) 포인트 적립 (등급별 적립률 적용)
        user.addTotalSpent(finalAmount);
        user.addPoints(earnedPointsSnapshot);

        // 7) 등급 재계산
        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        cartRepository.deleteByUserId(userId);

        // 8) 재고가 변경된 상품의 상세 캐시 무효화
        evictProductDetailCaches(orderLines.stream().map(OrderLine::productId).toList());

        return savedOrder;
    }

    private void evictProductDetailCaches(List<Long> productIds) {
        Cache cache = cacheManager.getCache("productDetail");
        if (cache == null) {
            return;
        }
        for (Long productId : productIds) {
            cache.evict(productId);
        }
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
}

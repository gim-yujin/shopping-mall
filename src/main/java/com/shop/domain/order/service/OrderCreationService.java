package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.global.outbox.OutboxEventPublisher;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.PaymentMethod;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.order.validation.OrderInvariantValidator;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
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
 * <p>OrderService(God Class)에서 분리: 장바구니 → 주문 변환, 재고 차감,
 * 쿠폰/포인트 처리, 결제 금액 계산 등 주문 생성에 필요한 모든 로직을 담당한다.</p>
 *
 * <h3>[Phase 3 코드 품질] createOrder() 메서드 분해</h3>
 *
 * <p><b>문제:</b> createOrder()가 250줄 이상의 단일 메서드로,
 * 장바구니 결정 → 재고 차감 → 쿠폰 할인 → 포인트 사용 → 주문 생성 → 후처리까지
 * 모든 단계를 한 메서드에서 처리했다. 코드 리뷰 시 개별 단계의 시작/끝을 파악하기 어렵고,
 * 특정 단계만 테스트하거나 수정할 때 관련 없는 코드를 읽어야 했다.</p>
 *
 * <p><b>해결:</b> 각 비즈니스 단계를 의미 있는 이름의 private 메서드로 추출한다.
 * createOrder()는 전체 흐름을 한눈에 파악할 수 있는 고수준 오케스트레이터가 되고,
 * 각 단계의 세부 로직은 해당 메서드 안에 캡슐화된다.
 * 메서드 간 데이터 전달은 내부 record 타입(CartSelection, StockDeductionResult, CouponResult)을
 * 사용하여 타입 안전하게 처리한다.</p>
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
    private final PointHistoryRepository pointHistoryRepository;
    private final EntityManager entityManager;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ShippingFeeCalculator shippingFeeCalculator;
    private final OrderInvariantValidator orderInvariantValidator;

    public OrderCreationService(OrderRepository orderRepository, CartRepository cartRepository,
                                ProductRepository productRepository, UserRepository userRepository,
                                ProductInventoryHistoryRepository inventoryHistoryRepository,
                                UserCouponRepository userCouponRepository,
                                UserTierRepository userTierRepository,
                                PointHistoryRepository pointHistoryRepository,
                                EntityManager entityManager,
                                OutboxEventPublisher outboxEventPublisher,
                                ShippingFeeCalculator shippingFeeCalculator,
                                OrderInvariantValidator orderInvariantValidator) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userCouponRepository = userCouponRepository;
        this.userTierRepository = userTierRepository;
        this.pointHistoryRepository = pointHistoryRepository;
        this.entityManager = entityManager;
        this.outboxEventPublisher = outboxEventPublisher;
        this.shippingFeeCalculator = shippingFeeCalculator;
        this.orderInvariantValidator = orderInvariantValidator;
    }

    /**
     * 주문을 생성한다.
     *
     * <p>[Phase 3 코드 품질] 고수준 오케스트레이터로 재구성.
     * 각 단계가 명확한 이름의 메서드로 분리되어 전체 흐름을 한눈에 파악할 수 있다.</p>
     */
    @Transactional
    public Order createOrder(Long userId, OrderCreateRequest request) {
        PaymentMethod paymentMethod = PaymentMethod.fromCode(request.paymentMethod())
                .orElseThrow(() -> new BusinessException("UNSUPPORTED_PAYMENT_METHOD", "지원하지 않는 결제수단"));

        // 같은 사용자의 동시 주문 요청을 트랜잭션 단위로 직렬화
        cartRepository.acquireUserCartLock(userId);

        // 1) 장바구니 항목 결정 (전체 / 선택 주문)
        CartSelection cartSelection = resolveCartItems(userId, request);

        // 2) 사용자 & 등급 정보 로드
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        UserTier tier = user.getTier();

        // 3) 재고 차감 & 주문 라인 생성
        StockDeductionResult stockResult = deductStockAndBuildOrderLines(
                cartSelection.items(), tier.getDiscountRate());

        // 4) 쿠폰 할인 적용
        CouponResult couponResult = applyCouponDiscount(request, stockResult.totalAmount(), userId);
        BigDecimal totalDiscount = stockResult.tierDiscountTotal().add(couponResult.discount());

        // 5) 포인트 사용
        int usePoints = processPointsUsage(request, user, stockResult.totalAmount(), totalDiscount);

        // 6) 배송비 & 최종 금액 계산
        BigDecimal shippingFee = shippingFeeCalculator.calculateShippingFee(tier, stockResult.totalAmount());
        BigDecimal usedPointsAmount = BigDecimal.valueOf(usePoints);
        BigDecimal finalAmount = shippingFeeCalculator.calculateFinalAmount(
                stockResult.totalAmount(), totalDiscount.add(usedPointsAmount), shippingFee);

        // 7) 주문 생성 & 저장
        Order savedOrder = buildAndSaveOrder(
                userId, paymentMethod, request, stockResult, couponResult,
                usePoints, shippingFee, finalAmount, tier);

        // 8) 후처리 (재고 이력, 쿠폰 사용, 등급 재계산, 장바구니 정리, 이벤트 발행)
        finalizeOrder(savedOrder, user, tier, stockResult, couponResult,
                cartSelection, usePoints);

        return savedOrder;
    }

    // ── 1단계: 장바구니 항목 결정 ────────────────────────────────

    /**
     * [Phase 3 코드 품질] 장바구니 선택 로직을 분리.
     *
     * <p><b>문제:</b> 전체 주문과 선택 주문의 분기 로직이 createOrder() 상단에
     * 25줄 이상 혼재하여, 이후 단계의 시작점을 파악하기 어려웠다.</p>
     *
     * <p><b>해결:</b> 장바구니 결정 로직을 별도 메서드로 추출하고,
     * 결과를 CartSelection record로 반환하여 isPartialOrder 플래그까지 캡슐화한다.</p>
     *
     * <p>[P1-6] cartItemIds가 null/빈 리스트이면 전체 장바구니를 주문한다 (기존 동작 호환).
     * 값이 있으면 해당 ID의 장바구니 항목만 주문 대상으로 사용한다.</p>
     */
    private CartSelection resolveCartItems(Long userId, OrderCreateRequest request) {
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

        return new CartSelection(cartItems, isPartialOrder);
    }

    // ── 3단계: 재고 차감 & 주문 라인 생성 ──────────────────────────

    /**
     * [Phase 3 코드 품질] 재고 차감 + 주문 라인 빌드 + 등급 할인 계산을 분리.
     *
     * <p><b>문제:</b> 상품별 재고 차감, 소계 계산, 등급 할인 계산, 재고 이력 스냅샷 생성이
     * 하나의 for 루프 안에 40줄 이상 혼재했다. 재고 관련 로직만 확인하려 해도
     * 할인 계산 코드를 함께 읽어야 했다.</p>
     *
     * <p><b>해결:</b> for 루프 전체를 별도 메서드로 추출하고, 결과를 StockDeductionResult로
     * 캡슐화하여 totalAmount, tierDiscountTotal, orderLines, inventorySnapshots를
     * 한 번에 반환한다.</p>
     */
    private StockDeductionResult deductStockAndBuildOrderLines(
            List<Cart> cartItems, BigDecimal tierDiscountRate) {
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal tierDiscountTotal = BigDecimal.ZERO;
        List<OrderLine> orderLines = new ArrayList<>();

        // [BUG FIX] 재고 이력을 Order save 이후에 저장하기 위해 임시 보관하는 리스트.
        // 기존 코드는 이 루프 안에서 inventoryHistoryRepository.save()를 호출했는데,
        // 이 시점에는 아직 Order가 persist되지 않아 reference_id(orderId)에 null이 전달되었다.
        // 주문 취소 시에는 orderId가 정상 전달되므로, 생성 쪽과 취소 쪽의 이력 일관성이 깨졌다.
        // 수정: 재고 차감은 즉시 수행하되, 이력 데이터는 InventorySnapshot으로 모아두고
        // Order가 저장된 후에 orderId를 포함하여 일괄 저장한다.
        List<InventorySnapshot> inventorySnapshots = new ArrayList<>();

        for (Cart cart : cartItems) {
            // [Phase 4] @Version 도입에 따른 L1 캐시 정합성 보장.
            //
            // 문제: Cart 조회 시 Product가 L1 캐시에 로드된다(version=N).
            // 이후 findByIdWithLock()이 PESSIMISTIC_WRITE 락을 획득하지만,
            // Hibernate는 L1 캐시의 기존 엔티티를 반환한다(DB 결과를 버림).
            // entityManager.refresh()가 필드 값은 갱신하지만, Hibernate 내부의
            // 스냅샷(dirty-checking 기준)이 갱신되지 않아 @Version 충돌이 발생한다.
            //
            // 해결: detach로 L1 캐시에서 제거한 뒤 findByIdWithLock()으로 재조회하면,
            // Hibernate가 DB 결과로 새 엔티티를 생성하여 올바른 version 스냅샷을 갖게 된다.
            Long productId = cart.getProduct().getProductId();
            entityManager.detach(cart.getProduct());

            Product product = productRepository.findByIdWithLock(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("상품", productId));

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

        return new StockDeductionResult(totalAmount, tierDiscountTotal, orderLines, inventorySnapshots);
    }

    // ── 4단계: 쿠폰 할인 적용 ──────────────────────────────────

    /**
     * [Phase 3 코드 품질] 쿠폰 검증 + 할인 계산을 분리.
     *
     * <p><b>문제:</b> 쿠폰 소유 검증, 사용 가능 여부 확인, 최소 주문금액 미달 처리,
     * 할인 금액 계산이 createOrder() 중간에 30줄 이상 산재하여,
     * 쿠폰 관련 비즈니스 규칙의 전체 그림을 파악하기 어려웠다.</p>
     *
     * <p><b>해결:</b> 쿠폰 처리 전체를 별도 메서드로 추출하고, CouponResult record로
     * 할인 금액과 UserCoupon 참조를 함께 반환한다.
     * 쿠폰 미사용 시 CouponResult.NONE이 반환된다.</p>
     */
    private CouponResult applyCouponDiscount(OrderCreateRequest request,
                                              BigDecimal totalAmount, Long userId) {
        if (request.userCouponId() == null) {
            return CouponResult.NONE;
        }

        UserCoupon userCoupon = userCouponRepository.findByIdWithLock(request.userCouponId())
                .orElseThrow(() -> new BusinessException("COUPON_NOT_FOUND", "쿠폰을 찾을 수 없습니다."));

        if (!userCoupon.getUserId().equals(userId)) {
            throw new BusinessException("COUPON_INVALID", "본인의 쿠폰만 사용할 수 있습니다.");
        }
        if (!userCoupon.isAvailable()) {
            throw new BusinessException("COUPON_EXPIRED", "사용할 수 없는 쿠폰입니다.");
        }

        // 쿠폰 최소 주문 기준은 "상품 금액(등급 할인/쿠폰 할인 전)" 기준으로 적용한다.
        BigDecimal couponDiscount = userCoupon.getCoupon().calculateDiscount(totalAmount);

        // [P0 BUG FIX] 쿠폰 할인이 0원이면 쿠폰을 사용하지 않는다.
        //
        // 기존 문제: Coupon.calculateDiscount()는 totalAmount < minOrderAmount이면
        // BigDecimal.ZERO를 반환하지만, 이후 로직에서 이 경우를 체크하지 않고
        // markAsUsedIfUnused()를 실행하여 할인 0원인데 쿠폰이 소진되었다.
        //
        // 수정: 최소 주문 금액 미달로 할인이 0원이면 명시적 에러를 반환한다.
        // 사용자가 의도적으로 쿠폰을 선택했으므로, 조용히 무시하는 것보다
        // 명확한 피드백을 제공하는 것이 UX상 올바르다.
        if (couponDiscount.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException("COUPON_MIN_ORDER_NOT_MET",
                    "쿠폰 최소 주문 금액(" +
                    String.format("%,.0f", userCoupon.getCoupon().getMinOrderAmount()) +
                    "원)에 미달하여 쿠폰을 적용할 수 없습니다.");
        }

        return new CouponResult(couponDiscount, userCoupon);
    }

    // ── 5단계: 포인트 사용 ──────────────────────────────────────

    /**
     * [Phase 3 코드 품질] 포인트 검증 + 사용 처리를 분리.
     *
     * <p><b>문제:</b> 포인트 보유량 확인, 사용 상한 클램핑, 잔액 차감 로직이
     * 쿠폰 처리 바로 뒤에 이어져 경계가 불명확했다.</p>
     *
     * <p><b>해결:</b> 포인트 관련 로직 전체를 분리하고, 실제 사용된 포인트 수를 반환한다.
     * 사용 상한 자동 조정(클램핑) 로직이 이 메서드 안에 캡슐화된다.</p>
     *
     * @return 실제 사용된 포인트 (클램핑 적용 후)
     */
    private int processPointsUsage(OrderCreateRequest request, User user,
                                    BigDecimal totalAmount, BigDecimal totalDiscount) {
        int usePoints = request.usePoints();
        if (usePoints <= 0) {
            return 0;
        }

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

        return usePoints;
    }

    // ── 7단계: 주문 빌드 & 저장 ────────────────────────────────

    private Order buildAndSaveOrder(Long userId, PaymentMethod paymentMethod,
                                     OrderCreateRequest request,
                                     StockDeductionResult stockResult,
                                     CouponResult couponResult,
                                     int usePoints, BigDecimal shippingFee,
                                     BigDecimal finalAmount, UserTier tier) {
        String orderNumber = generateOrderNumber();
        BigDecimal pointRateSnapshot = tier.getPointEarnRate();
        int earnedPointsSnapshot = finalAmount.multiply(pointRateSnapshot)
                .divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR).intValue();
        BigDecimal tierDiscountRate = tier.getDiscountRate();

        // [P2-11] 등급 할인과 쿠폰 할인을 분리하여 저장
        Order order = new Order(orderNumber, userId, stockResult.totalAmount(),
                stockResult.tierDiscountTotal().add(couponResult.discount()),
                stockResult.tierDiscountTotal(), couponResult.discount(),
                shippingFee, finalAmount, pointRateSnapshot, earnedPointsSnapshot,
                usePoints,
                paymentMethod.getCode(), request.shippingAddress(),
                request.recipientName(), request.recipientPhone());

        for (OrderLine orderLine : stockResult.orderLines()) {
            OrderItem item = new OrderItem(orderLine.productId(), orderLine.productName(),
                    orderLine.quantity(), orderLine.unitPrice(), tierDiscountRate, orderLine.subtotal());
            order.addItem(item);
        }

        order.markPaid();
        orderInvariantValidator.validateBeforePersist(order);
        return orderRepository.save(order);
    }

    // ── 8단계: 후처리 ──────────────────────────────────────────

    /**
     * [Phase 3 코드 품질] 주문 저장 후 후처리 단계를 분리.
     *
     * <p><b>문제:</b> 재고 이력 저장, 쿠폰 사용 처리, 누적 구매 금액 반영,
     * 등급 재계산, 장바구니 정리, Outbox 이벤트 발행이 createOrder() 하단에
     * 70줄 이상 나열되어 있었다. 주문 "생성" 로직과 "후처리" 로직의 경계가 불명확했다.</p>
     *
     * <p><b>해결:</b> 모든 후처리를 하나의 메서드로 묶어 createOrder()의 마지막 단계로
     * 명확히 구분한다.</p>
     */
    private void finalizeOrder(Order savedOrder, User user, UserTier tier,
                                StockDeductionResult stockResult, CouponResult couponResult,
                                CartSelection cartSelection, int usePoints) {
        Long userId = savedOrder.getUserId();

        // [BUG FIX] 재고 이력에 orderId를 포함하여 저장.
        // 기존: Order save 전에 inventoryHistory를 저장 → reference_id = null
        // 수정: Order save 후 savedOrder.getOrderId()로 정확한 주문 ID를 기록.
        for (InventorySnapshot snapshot : stockResult.inventorySnapshots()) {
            inventoryHistoryRepository.save(new ProductInventoryHistory(
                    snapshot.productId(), "OUT", snapshot.quantity(),
                    snapshot.beforeStock(), snapshot.afterStock(),
                    "ORDER", savedOrder.getOrderId(), userId
            ));
        }

        // 쿠폰 사용 처리 (DB 레벨 원자적 전환 보장)
        if (couponResult.userCoupon() != null) {
            int updatedRows = userCouponRepository.markAsUsedIfUnused(
                    couponResult.userCoupon().getUserCouponId(),
                    savedOrder.getOrderId(),
                    LocalDateTime.now()
            );
            if (updatedRows != 1) {
                throw new BusinessException("COUPON_ALREADY_USED", "이미 사용된 쿠폰입니다.");
            }
        }

        // 누적 구매 금액(total_spent) 반영
        user.addTotalSpent(savedOrder.getFinalAmount());

        // [P0 FIX] 포인트 적립을 배송 완료(DELIVERED) 시점으로 이연.
        // earnedPointsSnapshot은 Order에 저장하되, 실제 적립은
        // OrderService.settleEarnedPoints()에서 배송 완료 시에만 수행한다.

        // 포인트 사용 이력 기록
        if (usePoints > 0) {
            pointHistoryRepository.save(new PointHistory(
                    userId, PointHistory.USE, usePoints, user.getPointBalance(),
                    "ORDER", savedOrder.getOrderId(),
                    "주문 사용 (주문번호: " + savedOrder.getOrderNumber() + ")"
            ));
        }

        // 등급 재계산 (누적 구매 금액 기준)
        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        // [P1-6] 선택 주문인 경우 주문한 장바구니 항목만 삭제, 나머지는 유지한다.
        if (cartSelection.isPartialOrder()) {
            List<Long> orderedCartIds = cartSelection.items().stream().map(Cart::getCartId).toList();
            cartRepository.deleteAllById(orderedCartIds);
        } else {
            cartRepository.deleteByUserId(userId);
        }

        // [Outbox] 재고 변경 이벤트를 Outbox 테이블에 기록한다.
        outboxEventPublisher.publishStockChanged(
                stockResult.orderLines().stream().map(OrderLine::productId).toList()
        );
    }

    private String generateOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return datePart + "-" + randomPart;
    }

    // ── 내부 DTO ─────────────────────────────────────────────

    /** 장바구니 선택 결과를 캡슐화하는 내부 DTO. */
    private record CartSelection(List<Cart> items, boolean isPartialOrder) {
    }

    /**
     * 재고 차감 단계의 결과를 캡슐화하는 내부 DTO.
     * totalAmount, tierDiscountTotal, orderLines, inventorySnapshots를 한 번에 전달한다.
     */
    private record StockDeductionResult(BigDecimal totalAmount, BigDecimal tierDiscountTotal,
                                         List<OrderLine> orderLines,
                                         List<InventorySnapshot> inventorySnapshots) {
    }

    /**
     * 쿠폰 할인 단계의 결과를 캡슐화하는 내부 DTO.
     * 쿠폰 미사용 시 {@link #NONE}을 반환한다.
     */
    private record CouponResult(BigDecimal discount, UserCoupon userCoupon) {
        private static final CouponResult NONE = new CouponResult(BigDecimal.ZERO, null);
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

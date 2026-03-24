package com.shop.domain.order.service;

import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.PaymentMethod;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.order.validation.OrderInvariantValidator;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import com.shop.global.metrics.OrderMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
 * <p><b>해결:</b> createOrder()는 고수준 오케스트레이터로 유지하고,
 * 장바구니 선택/재고 처리/후처리는 전담 협력 클래스로 분리한다.
 * 쿠폰 할인과 포인트 사용처럼 주문 생성 문맥에 밀접한 계산만 이 서비스에 남긴다.</p>
 *
 * <h3>[Resilience4j] 주문 생성 서킷 브레이커</h3>
 * <p>{@code @CircuitBreaker(name = "orderCreation")}으로 전체 주문 생성 플로우에
 * 서킷 브레이커를 적용한다. DB 장애, 커넥션 풀 고갈 등 인프라 수준의 연속 실패가
 * 발생하면 서킷을 OPEN하여:</p>
 * <ul>
 *   <li>이미 장애 상태인 DB에 불필요한 주문 요청이 누적되는 것을 방지한다.</li>
 *   <li>사용자에게 즉시 "일시적 장애" 메시지를 반환하여 대기 시간을 줄인다.</li>
 *   <li>DB 복구 후 HALF_OPEN 시험 호출을 통해 자동으로 정상 상태로 복귀한다.</li>
 * </ul>
 * <p>쓰기 경로이므로 TimeLimiter 대신 DB 레벨 타임아웃(socketTimeout=30s,
 * lock_timeout=5s)에 의존하고, slowCallDurationThreshold(5s)로
 * 느린 호출을 감지하여 서킷 개방 여부를 판단한다.</p>
 */
@Service
@Transactional(readOnly = true)
public class OrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreationService.class);

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final UserCouponRepository userCouponRepository;
    private final ShippingFeeCalculator shippingFeeCalculator;
    private final OrderInvariantValidator orderInvariantValidator;
    private final OrderMetrics orderMetrics;
    private final OrderCartSelectionResolver cartSelectionResolver;
    private final OrderStockProcessor stockProcessor;
    private final OrderPostProcessor orderPostProcessor;

    public OrderCreationService(OrderRepository orderRepository,
                                UserRepository userRepository,
                                UserCouponRepository userCouponRepository,
                                ShippingFeeCalculator shippingFeeCalculator,
                                OrderInvariantValidator orderInvariantValidator,
                                OrderMetrics orderMetrics,
                                OrderCartSelectionResolver cartSelectionResolver,
                                OrderStockProcessor stockProcessor,
                                OrderPostProcessor orderPostProcessor) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.userCouponRepository = userCouponRepository;
        this.shippingFeeCalculator = shippingFeeCalculator;
        this.orderInvariantValidator = orderInvariantValidator;
        this.orderMetrics = orderMetrics;
        this.cartSelectionResolver = cartSelectionResolver;
        this.stockProcessor = stockProcessor;
        this.orderPostProcessor = orderPostProcessor;
    }

    /**
     * 주문을 생성한다.
     *
     * <p>[Phase 3 코드 품질] 고수준 오케스트레이터로 재구성.
     * 각 단계가 명확한 이름의 메서드로 분리되어 전체 흐름을 한눈에 파악할 수 있다.</p>
     *
     * <p>[Resilience4j] {@code orderCreation} 서킷 브레이커 + 리트라이 적용.
     * 어노테이션 기반이므로 Spring AOP 프록시를 통해 호출될 때만 동작한다.</p>
     *
     * <p>AOP 실행 순서 (외부 → 내부):
     * {@code @Retry → @CircuitBreaker → @Transactional → 비즈니스 로직}
     * <ul>
     *   <li>일시적 실패 시: @Transactional 롤백 → CB 실패 기록 → @Retry가 재시도</li>
     *   <li>서킷 OPEN 시: CB가 CallNotPermittedException → @Retry의 ignoreExceptions에
     *       등록되어 재시도 없이 즉시 폴백 실행</li>
     *   <li>재시도 소진 시: 최종 예외가 호출자에게 전파</li>
     * </ul></p>
     */
    @Retry(name = "orderCreation")
    @CircuitBreaker(name = "orderCreation", fallbackMethod = "createOrderFallback")
    @Transactional
    public Order createOrder(Long userId, OrderCreateRequest request) {
        // [Phase 13] 주문 생성 전체 소요 시간을 측정한다.
        // 비관적 잠금 대기, 쿠폰/포인트 처리, Outbox 이벤트 발행을 모두 포함하여
        // 동시성 병목 구간을 Grafana에서 시각적으로 식별할 수 있다.
        io.micrometer.core.instrument.Timer.Sample timerSample = orderMetrics.startTimer();
        try {
            Order order = executeCreateOrder(userId, request);
            orderMetrics.recordSuccess(timerSample);
            return order;
        } catch (Exception e) {
            orderMetrics.recordFailure(timerSample);
            throw e;
        }
    }

    /**
     * 주문 생성 내부 실행 로직.
     *
     * <p>[Phase 3 코드 품질] 고수준 오케스트레이터로 재구성.
     * 각 단계가 명확한 이름의 메서드로 분리되어 전체 흐름을 한눈에 파악할 수 있다.</p>
     *
     * <p>[Phase 13] 메트릭 수집을 위해 createOrder()에서 분리.
     * createOrder()가 타이머 래퍼 역할을 하고, 비즈니스 로직은 이 메서드에 캡슐화된다.</p>
     */
    private Order executeCreateOrder(Long userId, OrderCreateRequest request) {
        PaymentMethod paymentMethod = PaymentMethod.fromCode(request.paymentMethod())
                .orElseThrow(() -> new BusinessException("UNSUPPORTED_PAYMENT_METHOD", "지원하지 않는 결제수단"));

        // 1) 장바구니 항목 결정 (전체 / 선택 주문)
        OrderCartSelectionResolver.CartSelection cartSelection = cartSelectionResolver.resolve(userId, request);

        // 2) 사용자 & 등급 정보 로드
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));
        UserTier tier = user.getTier();

        // 3) 재고 차감 & 주문 라인 생성
        OrderStockProcessor.StockDeductionResult stockResult = stockProcessor.deductStockAndBuildOrderLines(
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
        orderPostProcessor.finalizeOrder(
                savedOrder, user, cartSelection, stockResult, couponResult.userCoupon(), usePoints);

        return savedOrder;
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
                                     OrderStockProcessor.StockDeductionResult stockResult,
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

        for (OrderStockProcessor.OrderLine orderLine : stockResult.orderLines()) {
            OrderItem item = new OrderItem(orderLine.productId(), orderLine.productName(),
                    orderLine.quantity(), orderLine.unitPrice(), tierDiscountRate, orderLine.subtotal());
            order.addItem(item);
        }

        order.markPaid();
        orderInvariantValidator.validateBeforePersist(order);
        return orderRepository.save(order);
    }

    private String generateOrderNumber() {
        String datePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return datePart + "-" + randomPart;
    }

    // ── Resilience4j 폴백 ──────────────────────────────────────

    /**
     * [Resilience4j] 주문 생성 서킷 브레이커 폴백 메서드.
     *
     * <p>서킷이 OPEN 상태일 때 호출된다. 주문 생성은 핵심 비즈니스 기능이므로
     * 대체 결과를 반환할 수 없다. 대신 사용자에게 일시적 장애를 알리는
     * BusinessException을 던져 명확한 에러 메시지를 전달한다.</p>
     *
     * <p>비즈니스 예외(재고 부족, 쿠폰 만료 등)는 서킷 브레이커의
     * {@code ignoreExceptions}에 등록되어 있으므로 이 폴백에 도달하지 않고
     * 원래 예외가 그대로 전파된다. 이 폴백은 인프라 장애
     * (DB 커넥션 실패, 타임아웃 등)에 의해 서킷이 OPEN된 경우에만 실행된다.</p>
     *
     * @param userId  사용자 ID (폴백 메서드 시그니처는 원본과 동일해야 함)
     * @param request 주문 요청 (폴백 메서드 시그니처는 원본과 동일해야 함)
     * @param e       서킷 브레이커가 전달하는 예외 (CallNotPermittedException 등)
     * @return 반환하지 않음 — 항상 BusinessException을 던진다
     */
    private Order createOrderFallback(Long userId, OrderCreateRequest request, Exception e) {
        // CallNotPermittedException: 서킷이 OPEN 상태여서 호출이 차단된 경우
        if (e instanceof CallNotPermittedException) {
            log.warn("[CircuitBreaker] 주문 생성 서킷 OPEN — 주문 차단. userId={}", userId);
            throw new BusinessException("SERVICE_UNAVAILABLE",
                    "주문 서비스가 일시적으로 불안정합니다. 잠시 후 다시 시도해주세요.");
        }

        // 인프라 장애(DB 타임아웃, 커넥션 실패 등)는 원본 예외를 전파한다.
        // 서킷 브레이커가 이 실패를 기록하여 누적 시 서킷을 OPEN한다.
        log.error("[CircuitBreaker] 주문 생성 실패 — 폴백 실행. userId={}, error={}",
                userId, e.getMessage());
        if (e instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException(e);
    }

    // ── 내부 DTO ─────────────────────────────────────────────

    /**
     * 쿠폰 할인 단계의 결과를 캡슐화하는 내부 DTO.
     * 쿠폰 미사용 시 {@link #NONE}을 반환한다.
     */
    private record CouponResult(BigDecimal discount, UserCoupon userCoupon) {
        private static final CouponResult NONE = new CouponResult(BigDecimal.ZERO, null);
    }
}

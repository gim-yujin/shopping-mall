package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.entity.UserTier;
import com.shop.domain.user.repository.UserRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 주문 서비스 파사드.
 *
 * 이전: 주문 생성, 취소, 조회, 배송비 계산, 상태 관리가 모두 하나의 클래스에 있었다.
 *       (280줄, 9개 의존성 — God Class)
 *
 * 이후: 역할별로 분리된 전문 서비스에 위임하되, 기존 public API는 그대로 유지한다.
 *   - ShippingFeeCalculator: 배송비/최종금액 순수 계산
 *   - OrderCreationService:  주문 생성 (재고 차감, 쿠폰/포인트 처리)
 *   - OrderCancellationService: 주문 취소 (재고 복구, 포인트 환불, 쿠폰 복원)
 *   - OrderQueryService:     조회 (사용자/관리자)
 *
 * updateOrderStatus는 조회 + 취소를 조합하는 조정(coordination) 로직이므로
 * 파사드에 유지한다.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderCreationService creationService;
    private final OrderCancellationService cancellationService;
    private final OrderQueryService queryService;
    private final ShippingFeeCalculator shippingFeeCalculator;
    private final OrderRepository orderRepository;
    private final PartialCancellationService partialCancellationService;
    private final UserRepository userRepository;
    private final PointHistoryRepository pointHistoryRepository;

    public OrderService(OrderCreationService creationService,
                        OrderCancellationService cancellationService,
                        OrderQueryService queryService,
                        ShippingFeeCalculator shippingFeeCalculator,
                        OrderRepository orderRepository,
                        PartialCancellationService partialCancellationService,
                        UserRepository userRepository,
                        PointHistoryRepository pointHistoryRepository) {
        this.creationService = creationService;
        this.cancellationService = cancellationService;
        this.queryService = queryService;
        this.shippingFeeCalculator = shippingFeeCalculator;
        this.orderRepository = orderRepository;
        this.partialCancellationService = partialCancellationService;
        this.userRepository = userRepository;
        this.pointHistoryRepository = pointHistoryRepository;
    }

    // ── 배송비/금액 계산 ──────────────────────────────────

    public BigDecimal calculateShippingFee(UserTier tier, BigDecimal itemTotalAmount) {
        return shippingFeeCalculator.calculateShippingFee(tier, itemTotalAmount);
    }

    public BigDecimal calculateFinalAmount(BigDecimal itemTotalAmount, BigDecimal totalDeduction, BigDecimal shippingFee) {
        return shippingFeeCalculator.calculateFinalAmount(itemTotalAmount, totalDeduction, shippingFee);
    }

    // ── 주문 생성 ─────────────────────────────────────────

    @Transactional
    public Order createOrder(Long userId, OrderCreateRequest request) {
        return creationService.createOrder(userId, request);
    }

    // ── 주문 조회 ─────────────────────────────────────────

    public Page<Order> getOrdersByUser(Long userId, Pageable pageable) {
        return queryService.getOrdersByUser(userId, pageable);
    }

    public Order getOrderDetail(Long orderId, Long userId) {
        return queryService.getOrderDetail(orderId, userId);
    }

    public Page<Order> getAllOrders(Pageable pageable) {
        return queryService.getAllOrders(pageable);
    }

    public Page<Order> getOrdersByStatus(String status, Pageable pageable) {
        return queryService.getOrdersByStatus(status, pageable);
    }

    // ── 주문 취소 ─────────────────────────────────────────

    @Transactional
    public void cancelOrder(Long orderId, Long userId) {
        cancellationService.cancelOrder(orderId, userId);
    }


    @Transactional
    public void partialCancel(Long orderId, Long userId, Long orderItemId, int quantity) {
        partialCancellationService.partialCancel(userId, orderId, orderItemId, quantity);
    }

    @Transactional
    public void requestReturn(Long orderId, Long userId, Long orderItemId, int quantity) {
        partialCancellationService.requestReturn(userId, orderId, orderItemId, quantity);
    }

    // ── 관리자 상태 변경 (조회 + 취소 조합) ──────────────────

    @Transactional
    public void updateOrderStatus(Long orderId, String status) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        OrderStatus targetStatus = OrderStatus.fromOrThrow(status);
        OrderStatus currentStatus = order.getOrderStatus();

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BusinessException(
                    "INVALID_STATUS_TRANSITION",
                    "허용되지 않는 주문 상태 전이입니다. [" + currentStatus + " -> " + targetStatus + "]"
            );
        }

        if (targetStatus == OrderStatus.CANCELLED && !order.isCancellable()) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    "취소할 수 없는 주문 상태입니다. [" + currentStatus + " -> " + targetStatus + "]");
        }

        switch (targetStatus) {
            case PAID -> order.markPaid();
            case SHIPPED -> order.markShipped();
            case DELIVERED -> {
                order.markDelivered();
                settleEarnedPoints(order);
            }
            case CANCELLED -> cancellationService.cancelOrderInternal(order, order.getUserId());
            case PENDING -> {
                // no-op
            }
        }
    }

    /**
     * [P0 FIX] 배송 완료 시 적립 포인트 정산.
     *
     * 기존 문제: 주문 생성 즉시 포인트가 적립(user.addPoints)되어,
     * 적립 포인트를 다른 주문에 사용한 뒤 첫 주문을 취소하면
     * 취소 시 음수→0 클램핑으로 인해 포인트 부당 지급이 발생했다.
     *
     * 수정: 주문 생성 시에는 earnedPointsSnapshot(적립 예정 금액)만 Order에 저장하고,
     * 실제 적립은 배송 완료(DELIVERED) 시에만 수행한다.
     * 취소 가능 상태(PENDING, PAID)에서는 points_settled=false이므로,
     * 취소 시 적립 포인트 차감 없이 사용 포인트 환불만 하면 된다.
     *
     * points_settled 플래그로 중복 정산을 방지한다(멱등성 보장).
     */
    private void settleEarnedPoints(Order order) {
        if (order.isPointsSettled()) {
            return;
        }

        int earned = order.getEarnedPointsSnapshot();
        if (earned > 0) {
            User user = userRepository.findByIdWithLockAndTier(order.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("사용자", order.getUserId()));
            user.addPoints(earned);

            pointHistoryRepository.save(new PointHistory(
                    user.getUserId(), PointHistory.EARN, earned, user.getPointBalance(),
                    "ORDER", order.getOrderId(),
                    "주문 적립 (주문번호: " + order.getOrderNumber() + ")"
            ));
        }
        order.settlePoints();
    }
}

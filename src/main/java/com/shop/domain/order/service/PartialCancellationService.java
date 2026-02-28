package com.shop.domain.order.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.domain.order.event.ProductStockChangedEvent;
import com.shop.domain.order.repository.OrderRepository;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.user.entity.User;
import com.shop.domain.user.repository.UserRepository;
import com.shop.domain.user.repository.UserTierRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 부분 취소/반품 전담 서비스.
 *
 * <h3>리뷰 2 P0/P1 보강 내역</h3>
 *
 * <p><b>P0-1 환불 금액 비례 계산:</b>
 * 기존 코드는 OrderItem.subtotal(할인 전 원가)을 기준으로 환불액을 계산했다.
 * 등급/쿠폰/포인트 할인이 적용된 주문에서 이 방식은 실결제금액보다 많은 금액을
 * 환불하는 과다 환불 버그를 일으킨다.
 * 수정: 환불액을 Order.finalAmount(배송비 제외) 대비 아이템 비중으로 비례 계산한다.</p>
 *
 * <p><b>P0-2 포인트 비례 환불:</b>
 * 기존 코드는 부분 취소 시 포인트 환불이 전혀 없었다.
 * 수정: usedPoints를 아이템 비중에 따라 비례 환불하고, PointHistory를 기록한다.
 * Order.refundedPoints로 환불 누계를 추적하여 초과 환불을 방지한다.</p>
 *
 * <p><b>P0-3 등급 재계산:</b>
 * 기존 코드는 addTotalSpent만 호출하고 등급 재계산이 없었다.
 * 수정: OrderCancellationService와 동일하게 userTierRepository로 등급을 재계산한다.</p>
 *
 * <p><b>P1-1 Order 비관적 잠금:</b>
 * 기존 코드는 OrderItem에만 락을 걸어, 같은 주문의 다른 아이템에 대한 동시 부분 취소 시
 * Order.refundedAmount에 lost update가 발생할 수 있었다.
 * 수정: Order를 먼저 비관적 잠금으로 획득하여 동일 주문에 대한 모든 취소 작업을 직렬화한다.</p>
 *
 * <p><b>P1-2 락 순서 통일:</b>
 * 기존 코드의 락 순서(OrderItem → User → Product)는 전체 취소(Order → Product → User)와
 * 불일치하여 교차 데드락 위험이 있었다.
 * 수정: 전체 취소와 동일하게 Order → Product → User 순서로 락을 획득한다.</p>
 *
 * <p><b>P1-3 캐시 무효화 이벤트:</b>
 * 기존 코드는 재고를 복구하지만 ProductStockChangedEvent를 발행하지 않아,
 * 상품 상세 캐시에 최대 2분(TTL) 동안 과거 재고가 표시되었다.
 * 수정: 재고 복구 후 이벤트를 발행하여 AFTER_COMMIT 리스너가 캐시를 무효화하도록 한다.</p>
 *
 * <p><b>P1-보너스 전체 아이템 취소 시 상태 전이:</b>
 * 모든 아이템의 remainingQuantity가 0이 되면 주문 상태를 CANCELLED로 전이한다.
 * (취소 가능 상태 PENDING/PAID에서만 적용, DELIVERED는 별도 반품완료 상태가 없으므로 유지)</p>
 */
@Service
public class PartialCancellationService {

    private static final Logger log = LoggerFactory.getLogger(PartialCancellationService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductInventoryHistoryRepository inventoryHistoryRepository;
    private final UserRepository userRepository;
    private final UserTierRepository userTierRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;

    public PartialCancellationService(OrderRepository orderRepository,
                                      ProductRepository productRepository,
                                      ProductInventoryHistoryRepository inventoryHistoryRepository,
                                      UserRepository userRepository,
                                      UserTierRepository userTierRepository,
                                      PointHistoryRepository pointHistoryRepository,
                                      EntityManager entityManager,
                                      ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
        this.userRepository = userRepository;
        this.userTierRepository = userTierRepository;
        this.pointHistoryRepository = pointHistoryRepository;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 부분 취소: PENDING 또는 PAID 상태의 주문에서 특정 아이템의 일부 수량을 취소한다.
     *
     * <p>락 획득 순서 (OrderCancellationService와 통일):
     * <ol>
     *   <li>Order (PESSIMISTIC_WRITE) — 동일 주문에 대한 동시 취소를 직렬화</li>
     *   <li>Product (PESSIMISTIC_WRITE) — 재고 정합성</li>
     *   <li>User (PESSIMISTIC_WRITE) — 포인트/누적금액/등급 정합성</li>
     * </ol>
     */
    @Transactional
    public void partialCancel(Long userId, Long orderId, Long orderItemId, int quantity) {
        // [P1-1] Order를 먼저 비관적 잠금으로 획득하여 refundedAmount lost update 방지
        Order order = orderRepository.findByIdAndUserIdWithLock(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        if (!order.isCancellable()) {
            throw new BusinessException("PARTIAL_CANCEL_NOT_ALLOWED",
                    "부분 취소 가능한 주문 상태가 아닙니다.");
        }

        OrderItem item = findItemInOrder(order, orderItemId);
        validateQuantity(item, quantity);

        // [P0-1] finalAmount 비례 환불 계산
        BigDecimal refundAmount = calculateProportionalRefund(order, item, quantity);
        // [P0-2] 포인트 비례 환불 계산
        int pointRefund = calculateProportionalPointRefund(order, item, quantity);

        applyRefund(userId, order, item, quantity, refundAmount, pointRefund, "PARTIAL_CANCEL");
    }

    /**
     * 반품 신청: DELIVERED 상태의 주문에서 특정 아이템의 일부 수량을 반품한다.
     */
    @Transactional
    public void requestReturn(Long userId, Long orderId, Long orderItemId, int quantity) {
        Order order = orderRepository.findByIdAndUserIdWithLock(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("RETURN_NOT_ALLOWED",
                    "반품은 배송완료 상태에서만 가능합니다.");
        }

        OrderItem item = findItemInOrder(order, orderItemId);
        validateQuantity(item, quantity);

        BigDecimal refundAmount = calculateProportionalRefund(order, item, quantity);
        int pointRefund = calculateProportionalPointRefund(order, item, quantity);

        applyRefund(userId, order, item, quantity, refundAmount, pointRefund, "RETURN");
    }

    // ── 내부 로직 ─────────────────────────────────────────

    /**
     * 환불 처리 공통 로직.
     *
     * <p>[P1-2] 락 순서를 전체 취소(OrderCancellationService)와 통일한다:
     * Order(이미 잠금) → Product → User.
     * 기존 코드는 User → Product 순서로 잠가 교차 데드락 위험이 있었다.</p>
     */
    private void applyRefund(Long userId, Order order, OrderItem item,
                             int quantity, BigDecimal refundAmount, int pointRefund,
                             String reason) {

        // ① Product 재고 복구 — 전체 취소와 동일한 락 순서 (Product 먼저)
        Product product = productRepository.findByIdWithLock(item.getProductId())
                .orElseThrow(() -> {
                    log.error("부분 취소 중 상품을 찾을 수 없습니다. orderId={}, productId={}",
                            order.getOrderId(), item.getProductId());
                    return new ResourceNotFoundException("상품", item.getProductId());
                });
        entityManager.refresh(product);
        int beforeStock = product.getStockQuantity();
        product.increaseStockAndRollbackSales(quantity);

        inventoryHistoryRepository.save(new ProductInventoryHistory(
                product.getProductId(), "IN", quantity,
                beforeStock, product.getStockQuantity(),
                reason, order.getOrderId(), userId));

        // ② User 누적금액 차감, 포인트 환불, 등급 재계산
        User user = userRepository.findByIdWithLockAndTier(userId)
                .orElseThrow(() -> new ResourceNotFoundException("사용자", userId));

        user.addTotalSpent(refundAmount.negate());

        // [P0-2] 포인트 비례 환불 + PointHistory 기록
        if (pointRefund > 0) {
            user.addPoints(pointRefund);
            order.addRefundedPoints(pointRefund);

            String description = ("RETURN".equals(reason) ? "반품" : "부분 취소")
                    + " 포인트 환불 (주문번호: " + order.getOrderNumber() + ")";
            pointHistoryRepository.save(new PointHistory(
                    userId, PointHistory.REFUND, pointRefund, user.getPointBalance(),
                    reason, order.getOrderId(), description));
        }

        // [P0-3] 등급 재계산 — 전체 취소(OrderCancellationService)와 동일
        userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
                .ifPresent(user::updateTier);

        // ③ OrderItem 및 Order 금액 갱신
        if ("RETURN".equals(reason)) {
            item.applyReturn(quantity, refundAmount);
        } else {
            item.applyPartialCancel(quantity, refundAmount);
        }
        order.addRefundedAmount(refundAmount);

        // ④ 전체 아이템 취소/반품 완료 시 주문 상태 전이
        //    PENDING/PAID 상태에서 모든 아이템이 취소되면 CANCELLED로 전환한다.
        //    DELIVERED 상태는 별도 FULLY_RETURNED 상태가 없으므로 유지한다.
        transitionIfFullyCancelled(order);

        // ⑤ [P1-3] 캐시 무효화 이벤트 발행 (AFTER_COMMIT 리스너에서 처리)
        eventPublisher.publishEvent(new ProductStockChangedEvent(
                List.of(item.getProductId())));
    }

    /**
     * [P0-1] 환불 금액을 실결제금액(finalAmount) 기준으로 비례 계산한다.
     *
     * <p>기존 버그: subtotal(할인 전 원가) 기준으로 계산하여 과다 환불이 발생했다.
     * 예: 10,000원 × 3개 = 30,000원 주문, 할인 4,500원 적용 → 실결제 25,500원.
     * 1개 부분 취소 시 기존 코드는 10,000원(원가 기준)을 환불했지만,
     * 정확한 환불액은 8,500원(실결제 비례)이다.</p>
     *
     * <p>계산 공식:
     * <pre>
     * 실제제품결제액 = finalAmount - shippingFee
     * 아이템비중   = item.subtotal / order.totalAmount
     * 환불금액     = 실제제품결제액 × 아이템비중 × (취소수량 / 주문수량)
     * </pre>
     * 배송비는 부분 취소 시 환불하지 않는다 (전체 취소에서만 환불).</p>
     *
     * @return 환불 금액 (0 이상, 기존 환불 누계를 초과하지 않음)
     */
    private BigDecimal calculateProportionalRefund(Order order, OrderItem item, int quantity) {
        // 실제 제품에 대한 결제액 = 최종결제금액 - 배송비
        BigDecimal effectivePaid = order.getFinalAmount().subtract(order.getShippingFee());

        if (effectivePaid.compareTo(BigDecimal.ZERO) <= 0
                || order.getTotalAmount().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // 비례 계산: 실결제액 × (아이템원가/전체원가) × (취소수량/주문수량)
        BigDecimal refund = effectivePaid
                .multiply(item.getSubtotal())
                .divide(order.getTotalAmount(), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP);

        // 안전 상한: 총 환불이 실결제금액을 초과하지 않도록 (반올림 누적 방지)
        BigDecimal maxRefundable = order.getFinalAmount().subtract(order.getRefundedAmount());
        if (refund.compareTo(maxRefundable) > 0) {
            refund = maxRefundable;
        }

        return refund.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : refund;
    }

    /**
     * [P0-2] 사용 포인트를 아이템 비중에 따라 비례 환불한다.
     *
     * <p>기존 버그: 부분 취소 시 포인트 환불이 전혀 없었다.
     * 예: 1,000P를 사용하여 3개 상품 주문 후 1개 부분 취소 시,
     * 약 333P가 환불되어야 하지만 0P가 환불되었다.</p>
     *
     * <p>Order.refundedPoints로 환불 누계를 추적하여
     * 반올림 누적에 의한 초과 환불을 방지한다.</p>
     *
     * @return 환불할 포인트 (0 이상, usedPoints - refundedPoints를 초과하지 않음)
     */
    private int calculateProportionalPointRefund(Order order, OrderItem item, int quantity) {
        if (order.getUsedPoints() <= 0) {
            return 0;
        }
        if (order.getTotalAmount().compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }

        // 비례 계산: usedPoints × (아이템원가/전체원가) × (취소수량/주문수량)
        int refund = BigDecimal.valueOf(order.getUsedPoints())
                .multiply(item.getSubtotal())
                .divide(order.getTotalAmount(), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(item.getQuantity()), 0, RoundingMode.FLOOR)
                .intValue();

        // 안전 상한: 총 환불 포인트가 사용 포인트를 초과하지 않도록
        int maxRefundable = order.getUsedPoints() - order.getRefundedPoints();
        return Math.min(refund, Math.max(0, maxRefundable));
    }

    /**
     * 모든 아이템의 잔여 수량이 0이면 주문 상태를 CANCELLED로 전이한다.
     * PENDING/PAID 상태에서만 적용하며, DELIVERED 상태는 유지한다.
     */
    private void transitionIfFullyCancelled(Order order) {
        boolean allZero = order.getItems().stream()
                .allMatch(i -> i.getRemainingQuantity() == 0);
        if (allZero && order.isCancellable()) {
            order.cancel();
        }
    }

    /**
     * Order의 items 컬렉션에서 대상 OrderItem을 찾는다.
     *
     * <p>[P1-1] Order에 이미 PESSIMISTIC_WRITE 잠금이 걸려 있으므로
     * OrderItem에 별도 잠금을 걸 필요가 없다. 동일 주문에 대한
     * 모든 동시 요청이 Order 잠금에서 직렬화된다.</p>
     */
    private OrderItem findItemInOrder(Order order, Long orderItemId) {
        return order.getItems().stream()
                .filter(i -> i.getOrderItemId().equals(orderItemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("주문상품", orderItemId));
    }

    private void validateQuantity(OrderItem item, int quantity) {
        int remaining = item.getRemainingQuantity();
        if (quantity <= 0 || quantity > remaining) {
            throw new BusinessException("INVALID_PARTIAL_QUANTITY",
                    "부분 취소/반품 수량이 유효하지 않습니다. (잔여: " + remaining + "개, 요청: " + quantity + "개)");
        }
    }
}

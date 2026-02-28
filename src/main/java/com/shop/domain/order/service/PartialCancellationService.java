package com.shop.domain.order.service;

import com.shop.domain.inventory.entity.ProductInventoryHistory;
import com.shop.domain.inventory.repository.ProductInventoryHistoryRepository;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderItemStatus;
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
import java.time.LocalDateTime;
import java.util.List;

/**
 * 부분 취소/반품 전담 서비스.
 *
 * <h3>Step 2 변경: requestReturn을 "신청만" 하도록 분리</h3>
 *
 * <p><b>문제:</b> 기존 requestReturn은 반품 신청과 동시에 재고 복구·환불·포인트 반환을
 * 모두 실행했다. 실제 커머스에서는 반품 사유 확인, 상품 상태 검수, 회수 물류 처리 후에
 * 환불이 이루어지므로 관리자 승인 단계가 필요했다.</p>
 *
 * <p><b>해결:</b> requestReturn은 OrderItem 상태를 RETURN_REQUESTED로만 전이하고,
 * 실제 환불 처리는 관리자가 호출하는 approveReturn에서 수행한다. 관리자가 거절하면
 * rejectReturn으로 상태만 RETURN_REJECTED로 전이하고 대기 수량을 원복한다.</p>
 *
 * <h3>메서드 역할 분담 (변경 후)</h3>
 * <table>
 *   <tr><th>메서드</th><th>호출자</th><th>상태 전이</th><th>재고/환불</th></tr>
 *   <tr><td>requestReturn</td><td>사용자</td><td>NORMAL → RETURN_REQUESTED</td><td>없음</td></tr>
 *   <tr><td>approveReturn</td><td>관리자</td><td>RETURN_REQUESTED → RETURNED</td><td>재고 복구 + 환불 실행</td></tr>
 *   <tr><td>rejectReturn</td><td>관리자</td><td>RETURN_REQUESTED → RETURN_REJECTED</td><td>없음</td></tr>
 *   <tr><td>partialCancel</td><td>사용자</td><td>(잔여=0이면 CANCELLED)</td><td>재고 복구 + 환불 실행</td></tr>
 * </table>
 *
 * <h3>리뷰 2 P0/P1 보강 내역 (기존 유지)</h3>
 *
 * <p><b>P0-1 환불 금액 비례 계산:</b>
 * 환불액을 Order.finalAmount(배송비 제외) 대비 아이템 비중으로 비례 계산한다.</p>
 *
 * <p><b>P0-2 포인트 비례 환불:</b>
 * usedPoints를 아이템 비중에 따라 비례 환불하고, PointHistory를 기록한다.</p>
 *
 * <p><b>P0-3 등급 재계산:</b>
 * OrderCancellationService와 동일하게 userTierRepository로 등급을 재계산한다.</p>
 *
 * <p><b>P1-1 Order 비관적 잠금:</b>
 * Order를 먼저 비관적 잠금으로 획득하여 동일 주문에 대한 모든 취소 작업을 직렬화한다.</p>
 *
 * <p><b>P1-2 락 순서 통일:</b>
 * 전체 취소와 동일하게 Order → Product → User 순서로 락을 획득한다.</p>
 *
 * <p><b>P1-3 캐시 무효화 이벤트:</b>
 * 재고 복구 후 ProductStockChangedEvent를 발행하여 캐시를 무효화한다.</p>
 */
@Service
public class PartialCancellationService {

    private static final Logger log = LoggerFactory.getLogger(PartialCancellationService.class);

    /**
     * 반품 허용 기간 (배송 완료 후 일수).
     *
     * <p>[P2-9 반품 기간 제한] 실제 커머스에서는 배송 완료 후 7~14일 이내로
     * 반품 기간을 제한한다. 무기한 반품 허용은 운영 리스크를 초래하므로
     * 14일로 제한한다. 향후 정책 변경 시 이 상수만 수정하거나
     * application.yml로 외부화할 수 있다.</p>
     */
    static final int RETURN_PERIOD_DAYS = 14;

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
     * 반품 신청: DELIVERED 상태의 주문에서 특정 아이템의 반품을 신청한다.
     *
     * <h3>Step 2 변경 사항</h3>
     * <p><b>변경 전:</b> 신청 즉시 재고 복구 + 환불 실행 (applyRefund 호출)<br>
     * <b>변경 후:</b> OrderItem 상태를 RETURN_REQUESTED로 전이하고,
     * 반품 대기 수량(pendingReturnQuantity)을 기록한다.
     * 실제 환불은 관리자 승인({@link #approveReturn}) 시 수행한다.</p>
     *
     * <p><b>왜 신청과 환불을 분리하는가?</b>
     * 실제 커머스에서는 반품 사유 확인, 상품 상태 검수, 회수 물류 처리 후에
     * 환불이 이루어진다. 신청 즉시 환불하면 허위 반품이나 상품 훼손 건에 대한
     * 사전 차단이 불가능하여 운영 손실이 발생한다.</p>
     *
     * <p>[P2-9 반품 기간 제한] 배송 완료일(deliveredAt)로부터 {@value RETURN_PERIOD_DAYS}일
     * 이내에만 반품을 허용한다.</p>
     *
     * @param userId       사용자 ID (주문 소유자 검증)
     * @param orderId      주문 ID
     * @param orderItemId  주문상품 ID
     * @param quantity     반품 신청 수량
     * @param returnReason 반품 사유 (DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER)
     */
    @Transactional
    public void requestReturn(Long userId, Long orderId, Long orderItemId,
                               int quantity, String returnReason) {
        Order order = orderRepository.findByIdAndUserIdWithLock(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        if (order.getOrderStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("RETURN_NOT_ALLOWED",
                    "반품은 배송완료 상태에서만 가능합니다.");
        }

        // [P2-9] 반품 기간 제한: 배송 완료 후 RETURN_PERIOD_DAYS일 이내만 허용
        validateReturnPeriod(order);

        OrderItem item = findItemInOrder(order, orderItemId);
        validateQuantity(item, quantity);

        // [Step 2] 상태 전이만 수행 — 재고/환불은 approveReturn에서 처리
        item.requestReturn(quantity, returnReason);
    }

    /**
     * [관리자] 반품 승인: RETURN_REQUESTED 상태의 아이템을 승인하고 환불을 실행한다.
     *
     * <h3>처리 내용</h3>
     * <ol>
     *   <li>Order 비관적 잠금 획득 (동시 처리 직렬화)</li>
     *   <li>아이템 상태가 RETURN_REQUESTED인지 검증</li>
     *   <li>pendingReturnQuantity 기반으로 환불 금액/포인트 비례 계산</li>
     *   <li>applyRefund로 재고 복구, 환불, 포인트, 등급, 캐시 무효화 실행</li>
     *   <li>OrderItem 상태를 RETURNED로 전이하고 수량/금액 확정</li>
     * </ol>
     *
     * <p><b>락 순서:</b> Order → Product → User (기존 전체 취소와 동일).
     * userId는 Order에서 조회하므로 파라미터로 받지 않는다.</p>
     *
     * <p><b>applyRefund와 item.approveReturn의 호출 순서:</b>
     * applyRefund를 먼저 호출하여 재고/환불/포인트를 처리한 후,
     * item.approveReturn으로 상태를 전이한다. applyRefund 내부에서
     * RETURN 사유일 때 item.applyReturn을 호출하지 않도록 변경하고,
     * 대신 item.approveReturn이 returnedQuantity/returnedAmount를 갱신한다.</p>
     *
     * @param orderId     주문 ID
     * @param orderItemId 주문상품 ID
     * @throws BusinessException INVALID_ITEM_STATUS — RETURN_REQUESTED가 아닌 상태
     */
    @Transactional
    public void approveReturn(Long orderId, Long orderItemId) {
        // 관리자 호출이므로 userId 검증 없이 Order를 직접 잠금
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        OrderItem item = findItemInOrder(order, orderItemId);

        // 상태 검증: RETURN_REQUESTED에서만 승인 가능
        if (item.getStatus() != OrderItemStatus.RETURN_REQUESTED) {
            throw new BusinessException("INVALID_ITEM_STATUS",
                    "반품 신청 상태의 아이템만 승인할 수 있습니다.");
        }

        int quantity = item.getPendingReturnQuantity();

        // 환불 금액/포인트 비례 계산
        BigDecimal refundAmount = calculateProportionalRefund(order, item, quantity);
        int pointRefund = calculateProportionalPointRefund(order, item, quantity);

        // 재고 복구 + 환불 + 포인트 + 등급 + 캐시 무효화 (기존 applyRefund 재사용)
        applyRefund(order.getUserId(), order, item, quantity,
                    refundAmount, pointRefund, "RETURN");

        // OrderItem 상태 전이: RETURN_REQUESTED → RETURNED + 수량/금액 확정
        item.approveReturn(quantity, refundAmount);

        // Order 환불 금액 갱신
        order.addRefundedAmount(refundAmount);
    }

    /**
     * [관리자] 반품 거절: RETURN_REQUESTED 상태의 아이템을 거절한다.
     *
     * <p>거절 시 재고/환불 변경 없이 상태만 RETURN_REJECTED로 전이하고,
     * 대기 수량(pendingReturnQuantity)을 0으로 원복하여 잔여 수량을 복원한다.
     * 사용자에게 거절 사유를 표시하여 투명한 운영을 보장한다.</p>
     *
     * <p>거절된 아이템은 반품 기간 내에 사용자가 재신청할 수 있다
     * (RETURN_REJECTED → RETURN_REQUESTED 전이 허용).</p>
     *
     * @param orderId      주문 ID
     * @param orderItemId  주문상품 ID
     * @param rejectReason 거절 사유 (관리자 입력)
     * @throws BusinessException INVALID_ITEM_STATUS_TRANSITION — RETURN_REQUESTED가 아닌 상태
     */
    @Transactional
    public void rejectReturn(Long orderId, Long orderItemId, String rejectReason) {
        // 관리자 호출이므로 userId 검증 없이 Order를 직접 잠금
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("주문", orderId));

        OrderItem item = findItemInOrder(order, orderItemId);

        // 상태 전이: RETURN_REQUESTED → RETURN_REJECTED
        // validateTransition은 OrderItem 내부에서 수행됨
        item.rejectReturn(rejectReason);
    }

    // ── 내부 로직 ─────────────────────────────────────────

    /**
     * 환불 처리 공통 로직.
     *
     * <p>[P1-2] 락 순서를 전체 취소(OrderCancellationService)와 통일한다:
     * Order(이미 잠금) → Product → User.
     * 기존 코드는 User → Product 순서로 잠가 교차 데드락 위험이 있었다.</p>
     *
     * <h3>Step 2 변경: RETURN 사유일 때 OrderItem 갱신 분리</h3>
     * <p>기존에는 applyRefund 내부에서 item.applyReturn()을 호출하여
     * returnedQuantity/returnedAmount를 갱신했다.
     * Step 2에서는 반품 승인 시 applyRefund 호출 후 별도로 item.approveReturn()을
     * 호출하여 상태 전이와 수량/금액 확정을 수행한다.
     * 따라서 RETURN 사유일 때는 applyRefund에서 아이템 수량/금액 갱신과
     * Order 환불 금액 갱신을 건너뛴다.</p>
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
        //    [Step 2] RETURN 사유일 때는 approveReturn() 호출부에서 별도 처리하므로 건너뛴다.
        //    PARTIAL_CANCEL 사유일 때만 여기서 직접 갱신한다.
        if ("PARTIAL_CANCEL".equals(reason)) {
            item.applyPartialCancel(quantity, refundAmount);
            order.addRefundedAmount(refundAmount);
        }
        // RETURN 사유: 호출부(approveReturn)에서 item.approveReturn() + order.addRefundedAmount() 수행

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

    /**
     * [P2-9] 배송 완료일로부터 반품 허용 기간이 경과했는지 검증한다.
     *
     * <p>배송 완료일(deliveredAt)이 null인 경우는 데이터 정합성 문제이므로
     * 반품을 거부한다. 정상적인 주문 플로우에서는 DELIVERED 상태 전이 시
     * deliveredAt이 반드시 설정되지만, 직접 DB 수정 등으로 누락될 수 있다.</p>
     *
     * @throws BusinessException RETURN_PERIOD_EXPIRED — 반품 기간 초과
     * @throws BusinessException RETURN_NOT_ALLOWED — deliveredAt 누락 (데이터 정합성 오류)
     */
    private void validateReturnPeriod(Order order) {
        LocalDateTime deliveredAt = order.getDeliveredAt();
        if (deliveredAt == null) {
            log.error("DELIVERED 상태이지만 deliveredAt이 null입니다. orderId={}", order.getOrderId());
            throw new BusinessException("RETURN_NOT_ALLOWED",
                    "배송 완료 일시 정보가 없어 반품을 처리할 수 없습니다.");
        }

        LocalDateTime returnDeadline = deliveredAt.plusDays(RETURN_PERIOD_DAYS);
        if (LocalDateTime.now().isAfter(returnDeadline)) {
            throw new BusinessException("RETURN_PERIOD_EXPIRED",
                    "반품 가능 기간이 지났습니다. (배송완료: " + deliveredAt.toLocalDate()
                            + ", 반품 마감: " + returnDeadline.toLocalDate() + ")");
        }
    }
}

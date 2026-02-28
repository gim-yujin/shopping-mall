package com.shop.domain.order.entity;

import com.shop.global.exception.BusinessException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 상품 엔티티.
 *
 * <h3>Step 1 변경: OrderItemStatus 상태 머신 도입</h3>
 *
 * <p><b>문제:</b> 기존에는 cancelledQuantity/returnedQuantity 수량 추적만으로
 * 아이템 상태를 표현했다. 반품 신청 시 관리자 확인 없이 즉시 환불이 실행되었고,
 * "반품 대기 중"이라는 중간 상태를 질의할 수 없었다.</p>
 *
 * <p><b>해결:</b> {@link OrderItemStatus} enum을 도입하여 상태 전이를 명시적으로
 * 관리한다. 반품 신청(RETURN_REQUESTED) → 관리자 승인(RETURNED) / 거절(RETURN_REJECTED)
 * 단계를 거치도록 하여 실제 커머스의 반품 검수 프로세스를 반영한다.</p>
 *
 * <p><b>pendingReturnQuantity 설계 결정:</b> 반품 신청 시 대기 수량을 별도 필드로
 * 추적한다(방법 A). status만으로 추론하는 방법 B는 부분 반품(3개 중 1개만 반품 신청)을
 * 표현할 수 없기 때문이다. {@link #getRemainingQuantity()}에 pendingReturnQuantity를
 * 포함하여 중복 반품 신청을 자동으로 방지한다.</p>
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "cancelled_quantity", nullable = false)
    private Integer cancelledQuantity;

    @Column(name = "returned_quantity", nullable = false)
    private Integer returnedQuantity;

    @Column(name = "cancelled_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal cancelledAmount;

    @Column(name = "returned_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal returnedAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ── Step 1 신규 필드 ─────────────────────────────────────

    /**
     * 아이템 상태.
     *
     * <p>DB에는 VARCHAR(20)으로 저장되며, CHECK 제약으로 유효 값만 허용한다.
     * JPA의 EnumType.STRING을 사용하여 가독성을 보장하고,
     * 새로운 상태 추가 시 ordinal 변경에 의한 데이터 불일치를 방지한다.</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderItemStatus status = OrderItemStatus.NORMAL;

    /**
     * 사용자 반품 사유.
     *
     * <p>반품 신청(RETURN_REQUESTED 전이) 시 사용자가 선택한 사유를 기록한다.
     * DEFECT(상품 불량), WRONG_ITEM(오배송), CHANGE_OF_MIND(단순 변심),
     * SIZE_ISSUE(사이즈 불일치), OTHER(기타) 등의 값이 올 수 있다.</p>
     */
    @Column(name = "return_reason", length = 500)
    private String returnReason;

    /**
     * 관리자 거절 사유.
     *
     * <p>관리자가 반품을 거절(RETURN_REJECTED 전이)할 때 입력하는 사유를 기록한다.
     * 사용자에게 거절 사유를 표시하여 투명한 운영을 보장한다.</p>
     */
    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /**
     * 반품 대기 수량.
     *
     * <p>반품 신청 시 이 필드에 요청 수량을 기록하고, getRemainingQuantity() 계산에
     * 포함하여 중복 반품 신청을 자동으로 방지한다.</p>
     *
     * <ul>
     *   <li>반품 신청(requestReturn): pendingReturnQuantity = 요청 수량</li>
     *   <li>관리자 승인(approveReturn): returnedQuantity += 수량, pendingReturnQuantity = 0</li>
     *   <li>관리자 거절(rejectReturn): pendingReturnQuantity = 0 (원복)</li>
     * </ul>
     */
    @Column(name = "pending_return_quantity", nullable = false)
    private Integer pendingReturnQuantity = 0;

    /** 반품 신청 일시. */
    @Column(name = "return_requested_at")
    private LocalDateTime returnRequestedAt;

    /** 반품 완료(관리자 승인) 일시. */
    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    // ── 생성자 ───────────────────────────────────────────────

    protected OrderItem() {}

    public OrderItem(Long productId, String productName, Integer quantity,
                     BigDecimal unitPrice, BigDecimal discountRate, BigDecimal subtotal) {
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.discountRate = discountRate;
        this.subtotal = subtotal;
        this.cancelledQuantity = 0;
        this.returnedQuantity = 0;
        this.cancelledAmount = BigDecimal.ZERO;
        this.returnedAmount = BigDecimal.ZERO;
        this.status = OrderItemStatus.NORMAL;
        this.pendingReturnQuantity = 0;
        this.createdAt = LocalDateTime.now();
    }

    // ── 연관관계 설정 ────────────────────────────────────────

    void setOrder(Order order) { this.order = order; }

    // ── 기존 Getter ──────────────────────────────────────────

    public Long getOrderItemId() { return orderItemId; }
    public Order getOrder() { return order; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscountRate() { return discountRate; }
    public BigDecimal getSubtotal() { return subtotal; }
    public Integer getCancelledQuantity() { return cancelledQuantity; }
    public Integer getReturnedQuantity() { return returnedQuantity; }
    public BigDecimal getCancelledAmount() { return cancelledAmount; }
    public BigDecimal getReturnedAmount() { return returnedAmount; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // ── Step 1 신규 Getter ───────────────────────────────────

    public OrderItemStatus getStatus() { return status; }
    public String getReturnReason() { return returnReason; }
    public String getRejectReason() { return rejectReason; }
    public Integer getPendingReturnQuantity() { return pendingReturnQuantity; }
    public LocalDateTime getReturnRequestedAt() { return returnRequestedAt; }
    public LocalDateTime getReturnedAt() { return returnedAt; }

    // ── 수량 계산 ────────────────────────────────────────────

    /**
     * 추가 작업(취소/반품 신청) 가능한 잔여 수량을 계산한다.
     *
     * <p><b>변경 사항:</b> 기존에는 {@code quantity - cancelledQuantity - returnedQuantity}였으나,
     * 반품 대기 수량(pendingReturnQuantity)을 추가로 차감하여 반품 진행 중인 수량에 대한
     * 중복 신청을 방지한다.</p>
     *
     * <p>예: 5개 주문, 1개 취소, 1개 반품 완료, 1개 반품 대기 → 잔여 2개</p>
     */
    public int getRemainingQuantity() {
        return quantity - cancelledQuantity - returnedQuantity - pendingReturnQuantity;
    }

    // ── 상태 전이 메서드 ──────────────────────────────────────

    /**
     * 사용자 반품 신청: NORMAL → RETURN_REQUESTED.
     *
     * <p>이 시점에서는 수량/금액 변경 없이 상태와 대기 수량만 기록한다.
     * 실제 재고 복구와 환불은 관리자 승인({@link #approveReturn}) 시 수행한다.</p>
     *
     * <p><b>왜 환불을 여기서 하지 않는가?</b>
     * 실제 커머스에서는 반품 사유 확인, 상품 상태 검수, 회수 물류 처리 후에
     * 환불이 이루어진다. 신청 즉시 환불하면 허위 반품이나 상품 훼손 건에 대한
     * 사전 차단이 불가능하다.</p>
     *
     * @param quantity     반품 신청 수량 (1 이상, 잔여 수량 이하)
     * @param returnReason 반품 사유 (DEFECT, WRONG_ITEM 등)
     * @throws BusinessException INVALID_ITEM_STATUS_TRANSITION — NORMAL이 아닌 상태에서 호출 시
     */
    public void requestReturn(int quantity, String returnReason) {
        validateTransition(OrderItemStatus.RETURN_REQUESTED);
        this.status = OrderItemStatus.RETURN_REQUESTED;
        this.returnReason = returnReason;
        this.pendingReturnQuantity = quantity;
        this.returnRequestedAt = LocalDateTime.now();
        // 주의: returnedQuantity, returnedAmount는 아직 변경하지 않는다.
        // 재고 복구와 환불은 approveReturn에서 수행한다.
    }

    /**
     * 관리자 반품 승인: RETURN_REQUESTED → RETURNED (Phase 1 자동 전이).
     *
     * <p>승인과 동시에 실제 수량/금액을 확정한다.</p>
     * <ul>
     *   <li>returnedQuantity += pendingReturnQuantity (확정된 반품 수량)</li>
     *   <li>returnedAmount += refundAmount (서비스 계층에서 비례 계산한 환불액)</li>
     *   <li>pendingReturnQuantity = 0 (대기 수량 소진)</li>
     * </ul>
     *
     * <p><b>Phase 1 설계:</b> 물류 시스템이 없으므로 RETURN_APPROVED → RETURNED를
     * 하나의 트랜지션으로 처리한다. 향후 물류 연동 시 RETURN_APPROVED에서 멈추고,
     * 회수 완료 이벤트를 받아 별도로 RETURNED로 전이하도록 확장 가능하다.</p>
     *
     * @param quantity     승인할 반품 수량 (= pendingReturnQuantity)
     * @param refundAmount 환불 금액 (서비스 계층에서 finalAmount 비례 계산)
     * @throws BusinessException INVALID_ITEM_STATUS_TRANSITION — RETURN_REQUESTED가 아닌 상태에서 호출 시
     */
    public void approveReturn(int quantity, BigDecimal refundAmount) {
        validateTransition(OrderItemStatus.RETURN_APPROVED);
        // Phase 1: RETURN_APPROVED를 거쳐 즉시 RETURNED로 전이
        this.status = OrderItemStatus.RETURNED;
        this.returnedQuantity += quantity;
        this.returnedAmount = this.returnedAmount.add(refundAmount);
        this.pendingReturnQuantity = 0;
        this.returnedAt = LocalDateTime.now();
    }

    /**
     * 관리자 반품 거절: RETURN_REQUESTED → RETURN_REJECTED.
     *
     * <p>거절 시 재고/환불 변경 없이 상태만 전이하고, 대기 수량을 원복한다.
     * 사용자에게 거절 사유를 표시하여 투명한 운영을 보장한다.</p>
     *
     * <p>거절된 아이템은 반품 기간 내에 재신청(RETURN_REJECTED → RETURN_REQUESTED)이
     * 허용된다. 이는 사용자가 추가 증빙을 첨부하여 재시도할 수 있도록 하기 위함이다.</p>
     *
     * @param rejectReason 거절 사유 (관리자 입력)
     * @throws BusinessException INVALID_ITEM_STATUS_TRANSITION — RETURN_REQUESTED가 아닌 상태에서 호출 시
     */
    public void rejectReturn(String rejectReason) {
        validateTransition(OrderItemStatus.RETURN_REJECTED);
        this.status = OrderItemStatus.RETURN_REJECTED;
        this.rejectReason = rejectReason;
        this.pendingReturnQuantity = 0;  // 대기 수량 원복 → getRemainingQuantity 복원
    }

    /**
     * 부분 취소 적용: 수량/금액 갱신 + 잔여 수량 0이면 CANCELLED 전이.
     *
     * <p><b>변경 사항:</b> 기존 로직에 상태 전이를 추가했다.
     * 부분 취소로 잔여 수량이 0이 되면 해당 아이템을 CANCELLED 상태로 전이한다.
     * 잔여 수량이 남아있으면 NORMAL 상태를 유지하여 추가 취소/반품이 가능하다.</p>
     *
     * @param quantity     취소 수량
     * @param refundAmount 환불 금액
     */
    public void applyPartialCancel(int quantity, BigDecimal refundAmount) {
        this.cancelledQuantity += quantity;
        this.cancelledAmount = this.cancelledAmount.add(refundAmount);
        // 잔여 수량이 0이 되면 종결 상태로 전이
        if (getRemainingQuantity() == 0) {
            this.status = OrderItemStatus.CANCELLED;
        }
    }

    /**
     * 반품 적용 (기존 호환용 — Step 2에서 approveReturn으로 대체 예정).
     *
     * <p>현재 PartialCancellationService.requestReturn()이 즉시 환불 시
     * 이 메서드를 호출한다. Step 2에서 requestReturn을 "신청만" 하도록 변경하면
     * 이 메서드 호출은 approveReturn으로 대체된다.</p>
     */
    public void applyReturn(int quantity, BigDecimal refundAmount) {
        this.returnedQuantity += quantity;
        this.returnedAmount = this.returnedAmount.add(refundAmount);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────

    /**
     * 상태 전이 유효성을 검증한다.
     *
     * <p>{@link OrderItemStatus#canTransitionTo}를 호출하여 현재 상태에서
     * 대상 상태로의 전이가 허용되는지 확인한다. 불법 전이 시
     * BusinessException을 발생시켜 비즈니스 불변 조건을 보장한다.</p>
     *
     * @param target 전이 대상 상태
     * @throws BusinessException INVALID_ITEM_STATUS_TRANSITION — 전이 불가 시
     */
    private void validateTransition(OrderItemStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new BusinessException("INVALID_ITEM_STATUS_TRANSITION",
                    "아이템 상태를 " + this.status.getLabel()
                            + "에서 " + target.getLabel() + "(으)로 변경할 수 없습니다.");
        }
    }
}

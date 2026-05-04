package com.shop.domain.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_number", unique = true, nullable = false, length = 50)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 20)
    private OrderStatus orderStatus;

    /**
     * [Phase 23-5] 주문 발행 경로 마커. 일반 주문은 NORMAL, 플래시 세일 경로는 FLASH_SALE.
     * 취소 시 보상 경로 분기에 사용된다(§13-2 #6 해소).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_origin", nullable = false, length = 20)
    private OrderOrigin orderOrigin = OrderOrigin.NORMAL;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount;

    /**
     * [P2-11] 등급 할인 금액.
     * 기존 discount_amount는 등급 할인 + 쿠폰 할인의 합산값이어서
     * 감사/정산 시 개별 할인 출처를 추적할 수 없었다.
     * 이 필드는 회원 등급(BRONZE~DIAMOND)에 의한 할인 금액만 기록한다.
     */
    @Column(name = "tier_discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal tierDiscountAmount;

    /**
     * [P2-11] 쿠폰 할인 금액.
     * 이 필드는 쿠폰 적용에 의한 할인 금액만 기록한다.
     * discount_amount = tier_discount_amount + coupon_discount_amount 관계가 성립한다.
     */
    @Column(name = "coupon_discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal couponDiscountAmount;

    @Column(name = "shipping_fee", nullable = false, precision = 8, scale = 2)
    private BigDecimal shippingFee;

    @Column(name = "final_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal finalAmount;

    @Column(name = "point_earn_rate_snapshot", nullable = false, precision = 5, scale = 2)
    private BigDecimal pointEarnRateSnapshot;

    @Column(name = "earned_points_snapshot", nullable = false)
    private Integer earnedPointsSnapshot;

    @Column(name = "used_points", nullable = false)
    private Integer usedPoints;

    @Column(name = "refunded_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal refundedAmount;

    /**
     * [P0 FIX] 부분취소/반품 시 환불된 포인트 누적.
     *
     * 기존 문제: PartialCancellationService가 포인트 비례 환불을 수행하지 않아
     * 포인트를 사용한 주문의 부분 취소 시 사용 포인트가 환불되지 않았다.
     *
     * 수정: 부분 취소/반품 시 usedPoints를 아이템 비중에 따라 비례 환불하고,
     * 환불된 포인트 누계를 이 필드에 기록하여 초과 환불을 방지한다.
     * 전체 취소 시에는 usedPoints 전액이 환불되므로 이 필드를 사용하지 않는다.
     */
    @Column(name = "refunded_points", nullable = false)
    private Integer refundedPoints;

    /**
     * [P0 FIX] 포인트 정산 완료 플래그.
     *
     * 기존 문제: 주문 생성 즉시 포인트가 적립되어, 적립 포인트를 다른 주문에
     * 사용한 뒤 첫 주문을 취소하면 포인트 부당 지급이 발생했다.
     *
     * 수정: 포인트 적립을 배송 완료(DELIVERED) 시점으로 이연한다.
     * 이 플래그는 배송 완료 시 TRUE로 전환되며, 중복 정산을 방지한다.
     * 취소 가능 상태(PENDING, PAID)에서는 항상 FALSE이므로,
     * 취소 시 적립 포인트 차감 없이 사용 포인트만 환불하면 된다.
     */
    @Column(name = "points_settled", nullable = false)
    private Boolean pointsSettled;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "recipient_name", length = 100)
    private String recipientName;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "order_date", nullable = false)
    private LocalDateTime orderDate;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * [3.6] 배송 추적번호.
     * 관리자가 주문 상태를 SHIPPED로 변경할 때 입력한다.
     * 배송 전 상태에서는 null이다.
     */
    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    /**
     * [3.6] 택배사명.
     * tracking_number와 함께 SHIPPED 전환 시 기록한다.
     */
    @Column(name = "carrier", length = 50)
    private String carrier;

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    /**
     * [Phase 23-2] 플래시 세일 전용 최소 주문 팩토리.
     *
     * <p>장바구니·쿠폰·포인트·티어 할인·배송비 계산을 모두 건너뛰고
     * 단일 상품을 할인가(sale_price) 그대로 주문으로 기록한다.
     * 배송비 0, 할인 0, 적립 0, PaymentMethod는 CARD 고정.
     * 배송 정보는 placeholder로 주문 상세 페이지에서 보충하는 구조
     * (Phase 23-3 이후 개선 여지).</p>
     */
    public static Order createForFlashSale(String orderNumber, Long userId,
                                            BigDecimal totalAmount, String paymentMethod) {
        Order o = new Order(orderNumber, userId, totalAmount,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, totalAmount,
                BigDecimal.ZERO, 0,
                0,
                paymentMethod,
                "(플래시 세일 주문 — 배송 정보 미입력)",
                "(플래시 세일)", "(플래시 세일)");
        o.orderOrigin = OrderOrigin.FLASH_SALE;
        o.markPaid();
        return o;
    }

    public Order(String orderNumber, Long userId, BigDecimal totalAmount, BigDecimal discountAmount,
                 BigDecimal tierDiscountAmount, BigDecimal couponDiscountAmount,
                 BigDecimal shippingFee, BigDecimal finalAmount,
                 BigDecimal pointEarnRateSnapshot, Integer earnedPointsSnapshot,
                 Integer usedPoints,
                 String paymentMethod,
                 String shippingAddress, String recipientName, String recipientPhone) {
        this.orderNumber = orderNumber;
        this.userId = userId;
        this.orderStatus = OrderStatus.PENDING;
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.tierDiscountAmount = tierDiscountAmount;
        this.couponDiscountAmount = couponDiscountAmount;
        this.shippingFee = shippingFee;
        this.finalAmount = finalAmount;
        this.pointEarnRateSnapshot = pointEarnRateSnapshot;
        this.earnedPointsSnapshot = earnedPointsSnapshot;
        this.usedPoints = usedPoints;
        this.refundedAmount = BigDecimal.ZERO;
        this.refundedPoints = 0;
        this.pointsSettled = false;
        this.paymentMethod = paymentMethod;
        this.shippingAddress = shippingAddress;
        this.recipientName = recipientName;
        this.recipientPhone = recipientPhone;
        this.orderDate = LocalDateTime.now();
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void markPaid() {
        this.orderStatus = OrderStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    public void markShipped() {
        this.orderStatus = OrderStatus.SHIPPED;
        this.shippedAt = LocalDateTime.now();
    }

    /**
     * [3.6] 배송 정보와 함께 배송 시작 처리.
     * 관리자가 SHIPPED 상태로 전환할 때 택배사와 송장번호를 함께 기록한다.
     */
    public void markShipped(String carrier, String trackingNumber) {
        this.orderStatus = OrderStatus.SHIPPED;
        this.shippedAt = LocalDateTime.now();
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
    }

    public void markDelivered() {
        this.orderStatus = OrderStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    public void cancel() {
        this.orderStatus = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public boolean isCancellable() {
        return orderStatus == OrderStatus.PENDING || orderStatus == OrderStatus.PAID;
    }

    public void addRefundedAmount(BigDecimal amount) {
        this.refundedAmount = this.refundedAmount.add(amount);
    }

    public void addRefundedPoints(int points) {
        this.refundedPoints += points;
    }

    /**
     * [P0 FIX] 포인트 정산 완료 처리.
     * 배송 완료(DELIVERED) 시 호출되어, 적립 포인트가 사용자 잔액에 반영되었음을 기록한다.
     * 이 메서드가 호출된 후에는 isPointsSettled() == true가 되어 중복 정산을 방지한다.
     */
    public void settlePoints() {
        this.pointsSettled = true;
    }

    public boolean isPointsSettled() {
        return pointsSettled;
    }

    // Getters
    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public Long getUserId() { return userId; }
    public OrderStatus getOrderStatus() { return orderStatus; }
    public String getOrderStatusCode() { return orderStatus.name(); }
    public OrderOrigin getOrderOrigin() { return orderOrigin; }
    public boolean isFlashSaleOrder() { return orderOrigin == OrderOrigin.FLASH_SALE; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTierDiscountAmount() { return tierDiscountAmount; }
    public BigDecimal getCouponDiscountAmount() { return couponDiscountAmount; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public BigDecimal getPointEarnRateSnapshot() { return pointEarnRateSnapshot; }
    public Integer getEarnedPointsSnapshot() { return earnedPointsSnapshot; }
    public Integer getUsedPoints() { return usedPoints; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public Integer getRefundedPoints() { return refundedPoints; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getShippingAddress() { return shippingAddress; }
    public String getRecipientName() { return recipientName; }
    public String getRecipientPhone() { return recipientPhone; }
    public LocalDateTime getOrderDate() { return orderDate; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getCarrier() { return carrier; }
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public List<OrderItem> getItems() { return items; }

    public String getStatusDisplay() {
        return orderStatus.getLabel();
    }
}

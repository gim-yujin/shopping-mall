package com.shop.domain.order.entity;

import jakarta.persistence.*;
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

    @Column(name = "shipped_at")
    private LocalDateTime shippedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

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
    public LocalDateTime getDeliveredAt() { return deliveredAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public List<OrderItem> getItems() { return items; }
    
    public String getStatusDisplay() {
        return orderStatus.getLabel();
    }
}

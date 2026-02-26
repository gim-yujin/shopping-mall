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
        this.shippingFee = shippingFee;
        this.finalAmount = finalAmount;
        this.pointEarnRateSnapshot = pointEarnRateSnapshot;
        this.earnedPointsSnapshot = earnedPointsSnapshot;
        this.usedPoints = usedPoints;
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

    // Getters
    public Long getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public Long getUserId() { return userId; }
    public OrderStatus getOrderStatus() { return orderStatus; }
    public String getOrderStatusCode() { return orderStatus.name(); }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getShippingFee() { return shippingFee; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public BigDecimal getPointEarnRateSnapshot() { return pointEarnRateSnapshot; }
    public Integer getEarnedPointsSnapshot() { return earnedPointsSnapshot; }
    public Integer getUsedPoints() { return usedPoints; }
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

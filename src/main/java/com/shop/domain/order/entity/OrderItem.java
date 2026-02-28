package com.shop.domain.order.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
        this.createdAt = LocalDateTime.now();
    }

    void setOrder(Order order) { this.order = order; }

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
    public int getRemainingQuantity() { return quantity - cancelledQuantity - returnedQuantity; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void applyPartialCancel(int quantity, BigDecimal refundAmount) {
        this.cancelledQuantity += quantity;
        this.cancelledAmount = this.cancelledAmount.add(refundAmount);
    }

    public void applyReturn(int quantity, BigDecimal refundAmount) {
        this.returnedQuantity += quantity;
        this.returnedAmount = this.returnedAmount.add(refundAmount);
    }
}

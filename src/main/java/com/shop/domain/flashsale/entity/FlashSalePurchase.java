package com.shop.domain.flashsale.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "flash_sale_purchases")
public class FlashSalePurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "flash_sale_purchase_id")
    private Long flashSalePurchaseId;

    @Column(name = "flash_sale_id", nullable = false)
    private Long flashSaleId;

    @Column(name = "flash_sale_item_id", nullable = false)
    private Long flashSaleItemId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "purchased_at", nullable = false)
    private LocalDateTime purchasedAt;

    protected FlashSalePurchase() {}

    public static FlashSalePurchase record(Long flashSaleId, Long flashSaleItemId, Long userId, Long orderId) {
        FlashSalePurchase p = new FlashSalePurchase();
        p.flashSaleId = flashSaleId;
        p.flashSaleItemId = flashSaleItemId;
        p.userId = userId;
        p.orderId = orderId;
        p.purchasedAt = LocalDateTime.now();
        return p;
    }

    public Long getFlashSalePurchaseId() { return flashSalePurchaseId; }
    public Long getFlashSaleId() { return flashSaleId; }
    public Long getFlashSaleItemId() { return flashSaleItemId; }
    public Long getUserId() { return userId; }
    public Long getOrderId() { return orderId; }
    public LocalDateTime getPurchasedAt() { return purchasedAt; }
}

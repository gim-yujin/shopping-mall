package com.shop.domain.inventory.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "product_inventory_history")
public class ProductInventoryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType;

    @Column(name = "change_amount", nullable = false)
    private Integer changeAmount;

    @Column(name = "before_quantity", nullable = false)
    private Integer beforeQuantity;

    @Column(name = "after_quantity", nullable = false)
    private Integer afterQuantity;

    @Column(name = "reason", length = 100)
    private String reason;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    protected ProductInventoryHistory() {}

    public ProductInventoryHistory(Long productId, String changeType, int changeAmount,
                                    int beforeQuantity, int afterQuantity,
                                    String reason, Long referenceId, Long createdBy) {
        this.productId = productId;
        this.changeType = changeType;
        this.changeAmount = changeAmount;
        this.beforeQuantity = beforeQuantity;
        this.afterQuantity = afterQuantity;
        this.reason = reason;
        this.referenceId = referenceId;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getHistoryId() { return historyId; }
    public Long getProductId() { return productId; }
    public String getChangeType() { return changeType; }
    public Integer getChangeAmount() { return changeAmount; }
    public Integer getBeforeQuantity() { return beforeQuantity; }
    public Integer getAfterQuantity() { return afterQuantity; }
    public String getReason() { return reason; }
    public Long getReferenceId() { return referenceId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Long getCreatedBy() { return createdBy; }
}

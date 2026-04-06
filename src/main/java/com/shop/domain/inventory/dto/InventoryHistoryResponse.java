package com.shop.domain.inventory.dto;

import com.shop.domain.inventory.entity.ProductInventoryHistory;

import java.time.LocalDateTime;

public record InventoryHistoryResponse(
        Long historyId,
        Long productId,
        String changeType,
        int changeAmount,
        int beforeQuantity,
        int afterQuantity,
        String reason,
        Long referenceId,
        Long createdBy,
        LocalDateTime createdAt
) {
    public static InventoryHistoryResponse from(ProductInventoryHistory history) {
        return new InventoryHistoryResponse(
                history.getHistoryId(),
                history.getProductId(),
                history.getChangeType(),
                history.getChangeAmount(),
                history.getBeforeQuantity(),
                history.getAfterQuantity(),
                history.getReason(),
                history.getReferenceId(),
                history.getCreatedBy(),
                history.getCreatedAt()
        );
    }
}

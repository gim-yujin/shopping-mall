package com.shop.domain.point.dto;

import com.shop.domain.point.entity.PointHistory;

import java.time.LocalDateTime;

public record PointHistoryResponse(
        Long historyId,
        String changeType,
        int amount,
        int balanceAfter,
        String referenceType,
        Long referenceId,
        String description,
        LocalDateTime createdAt
) {
    public static PointHistoryResponse from(PointHistory history) {
        return new PointHistoryResponse(
                history.getHistoryId(),
                history.getChangeType(),
                history.getAmount(),
                history.getBalanceAfter(),
                history.getReferenceType(),
                history.getReferenceId(),
                history.getDescription(),
                history.getCreatedAt()
        );
    }
}

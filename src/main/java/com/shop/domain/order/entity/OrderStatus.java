package com.shop.domain.order.entity;

import com.shop.global.exception.BusinessException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum OrderStatus {
    PENDING("결제대기", "bg-yellow-100 text-yellow-700", 0),
    PAID("결제완료", "bg-yellow-100 text-yellow-700", 1),
    SHIPPED("배송중", "bg-blue-100 text-blue-700", 2),
    DELIVERED("배송완료", "bg-green-100 text-green-700", 3),
    CANCELLED("주문취소", "bg-red-100 text-red-700", -1);

    private final String label;
    private final String badgeClass;
    private final int progressIndex;

    OrderStatus(String label, String badgeClass, int progressIndex) {
        this.label = label;
        this.badgeClass = badgeClass;
        this.progressIndex = progressIndex;
    }

    public String getLabel() {
        return label;
    }

    public String getBadgeClass() {
        return badgeClass;
    }

    public int getProgressIndex() {
        return progressIndex;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == PENDING || target == PAID || target == CANCELLED;
            case PAID -> target == PAID || target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == SHIPPED || target == DELIVERED;
            case DELIVERED -> target == DELIVERED;
            case CANCELLED -> target == CANCELLED;
        };
    }

    public static Optional<OrderStatus> from(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(status -> status.name().equals(rawStatus.trim().toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    public static OrderStatus fromOrThrow(String rawStatus) {
        return from(rawStatus)
                .orElseThrow(() -> new BusinessException("INVALID_STATUS", "잘못된 주문 상태입니다."));
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(OrderStatus::name).toList();
    }

    public static Map<String, String> labelsByCode() {
        Map<String, String> result = new LinkedHashMap<>();
        for (OrderStatus status : values()) {
            result.put(status.name(), status.getLabel());
        }
        return result;
    }

    public static Map<String, String> badgeClassesByCode() {
        Map<String, String> result = new LinkedHashMap<>();
        for (OrderStatus status : values()) {
            result.put(status.name(), status.getBadgeClass());
        }
        return result;
    }
}

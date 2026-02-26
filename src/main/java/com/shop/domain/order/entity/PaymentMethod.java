package com.shop.domain.order.entity;

import java.util.Arrays;
import java.util.Optional;

public enum PaymentMethod {
    CARD("신용/체크카드"),
    BANK("계좌이체"),
    KAKAO("카카오페이"),
    NAVER("네이버페이"),
    PAYCO("페이코");

    private final String displayName;

    PaymentMethod(String displayName) {
        this.displayName = displayName;
    }

    public String getCode() {
        return name();
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Optional<PaymentMethod> fromCode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(method -> method.name().equals(value.trim().toUpperCase()))
                .findFirst();
    }
}

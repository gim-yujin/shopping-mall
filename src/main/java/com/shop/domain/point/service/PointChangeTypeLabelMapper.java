package com.shop.domain.point.service;

import java.util.Locale;

/**
 * 포인트 이력 changeType을 화면용으로 정규화/한글 라벨 변환한다.
 */
public final class PointChangeTypeLabelMapper {

    private PointChangeTypeLabelMapper() {
    }

    public static String normalize(String changeType) {
        if (changeType == null) {
            return "UNKNOWN";
        }
        return changeType.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    public static String toKoreanLabel(String changeType) {
        return switch (normalize(changeType)) {
            case "EARN" -> "적립";
            case "USE" -> "사용";
            case "REFUND" -> "환불";
            case "EXPIRE" -> "만료";
            case "ADJUST" -> "조정";
            default -> "기타";
        };
    }
}

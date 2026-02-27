package com.shop.domain.coupon.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Coupon 엔티티 단위 테스트 — isValid, calculateDiscount, incrementUsed
 */
class CouponEntityUnitTest {

    /**
     * protected 생성자만 있는 Coupon을 리플렉션으로 생성
     */
    private Coupon createCoupon(String discountType, BigDecimal discountValue,
                                BigDecimal minOrderAmount, BigDecimal maxDiscount,
                                Integer totalQuantity, int usedQuantity,
                                boolean isActive, LocalDateTime validFrom, LocalDateTime validUntil) throws Exception {
        Coupon coupon = Coupon.class.getDeclaredConstructor().newInstance();
        setField(coupon, "discountType", DiscountType.valueOf(discountType));
        setField(coupon, "discountValue", discountValue);
        setField(coupon, "minOrderAmount", minOrderAmount);
        setField(coupon, "maxDiscount", maxDiscount);
        setField(coupon, "totalQuantity", totalQuantity);
        setField(coupon, "usedQuantity", usedQuantity);
        setField(coupon, "isActive", isActive);
        setField(coupon, "validFrom", validFrom);
        setField(coupon, "validUntil", validUntil);
        return coupon;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== isValid ====================

    @Test
    @DisplayName("isValid — 활성, 기간 내 → true")
    void isValid_allConditionsMet_returnsTrue() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 50,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.isValid()).isTrue();
    }

    @Test
    @DisplayName("isValid — 비활성 쿠폰 → false")
    void isValid_inactive_returnsFalse() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                false, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.isValid()).isFalse();
    }

    @Test
    @DisplayName("isValid — 기간 만료 → false")
    void isValid_expired_returnsFalse() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                true, LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1));

        assertThat(coupon.isValid()).isFalse();
    }

    @Test
    @DisplayName("isValid — 아직 시작 전 → false")
    void isValid_notYetStarted_returnsFalse() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                true, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));

        assertThat(coupon.isValid()).isFalse();
    }

    @Test
    @DisplayName("isValid — 수량 소진이어도 활성/기간 유효하면 true")
    void isValid_quantityExhausted_returnsTrue() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 100,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.isValid()).isTrue();
    }

    @Test
    @DisplayName("isIssuable — 수량 소진이면 false")
    void isIssuable_quantityExhausted_returnsFalse() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 100,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.isIssuable()).isFalse();
    }

    @Test
    @DisplayName("isValid — validFrom 경계 시각 포함 → true")
    void isValid_atValidFromBoundary_returnsTrue() throws Exception {
        LocalDateTime validFrom = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                true, validFrom, validFrom.plusDays(30));

        // validFrom과 정확히 같은 시각 → 유효
        assertThat(coupon.isValid(validFrom)).isTrue();
    }

    @Test
    @DisplayName("isValid — validUntil 경계 시각 포함 → true")
    void isValid_atValidUntilBoundary_returnsTrue() throws Exception {
        LocalDateTime validUntil = LocalDateTime.of(2025, 1, 31, 23, 59, 59);
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                true, validUntil.minusDays(30), validUntil);

        // validUntil과 정확히 같은 시각 → 유효
        assertThat(coupon.isValid(validUntil)).isTrue();
    }

    @Test
    @DisplayName("isValid — validUntil 1나노초 초과 → false")
    void isValid_afterValidUntilByOneNano_returnsFalse() throws Exception {
        LocalDateTime validUntil = LocalDateTime.of(2025, 1, 31, 23, 59, 59);
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                true, validUntil.minusDays(30), validUntil);

        // validUntil보다 1나노초 뒤 → 무효
        assertThat(coupon.isValid(validUntil.plusNanos(1))).isFalse();
    }

    @Test
    @DisplayName("isValid — validFrom 1나노초 전 → false")
    void isValid_beforeValidFromByOneNano_returnsFalse() throws Exception {
        LocalDateTime validFrom = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 0,
                true, validFrom, validFrom.plusDays(30));

        // validFrom보다 1나노초 전 → 무효
        assertThat(coupon.isValid(validFrom.minusNanos(1))).isFalse();
    }

    @Test
    @DisplayName("isValid — totalQuantity null (무제한) → true")
    void isValid_unlimitedQuantity_returnsTrue() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, null, 9999,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.isValid()).isTrue();
    }

    // ==================== calculateDiscount ====================

    @Test
    @DisplayName("calculateDiscount — FIXED 할인 적용")
    void calculateDiscount_fixed_returnsDiscountValue() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(3000),
                BigDecimal.ZERO, null, null, 0,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        BigDecimal discount = coupon.calculateDiscount(BigDecimal.valueOf(50000));
        assertThat(discount).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("calculateDiscount — PERCENT 할인 적용")
    void calculateDiscount_percent_calculatesCorrectly() throws Exception {
        Coupon coupon = createCoupon("PERCENT", BigDecimal.valueOf(10),
                BigDecimal.ZERO, null, null, 0,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        BigDecimal discount = coupon.calculateDiscount(BigDecimal.valueOf(50000));
        assertThat(discount).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("calculateDiscount — PERCENT 할인 + maxDiscount 상한 적용")
    void calculateDiscount_percent_cappedByMaxDiscount() throws Exception {
        Coupon coupon = createCoupon("PERCENT", BigDecimal.valueOf(20),
                BigDecimal.ZERO, BigDecimal.valueOf(5000), null, 0,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        // 20% of 50000 = 10000, but max is 5000
        BigDecimal discount = coupon.calculateDiscount(BigDecimal.valueOf(50000));
        assertThat(discount).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("calculateDiscount — 최소 주문 금액 미달 → 할인 0원")
    void calculateDiscount_belowMinOrder_returnsZero() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(3000),
                BigDecimal.valueOf(30000), null, null, 0,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        BigDecimal discount = coupon.calculateDiscount(BigDecimal.valueOf(20000));
        assertThat(discount).isEqualByComparingTo("0");
    }

    // ==================== incrementUsed ====================

    @Test
    @DisplayName("incrementUsed — usedQuantity 1 증가")
    void incrementUsed_incrementsByOne() throws Exception {
        Coupon coupon = createCoupon("FIXED", BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null, 100, 5,
                true, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        coupon.incrementUsed();
        assertThat(coupon.getUsedQuantity()).isEqualTo(6);

        coupon.incrementUsed();
        assertThat(coupon.getUsedQuantity()).isEqualTo(7);
    }
}

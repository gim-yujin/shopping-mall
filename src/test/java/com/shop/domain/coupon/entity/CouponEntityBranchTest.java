package com.shop.domain.coupon.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coupon 엔티티 분기 커버리지 보강 테스트.
 *
 * <p>기존 CouponEntityUnitTest에서 다루지 않은 분기를 검증한다:
 * - update: 모든 필드 갱신
 * - toggleActive: isActive 토글
 * - isIssuable(LocalDateTime): 시각 기준 발급 가능 여부
 * - isQuantityExhausted: totalQuantity null(무제한) vs 소진
 * - 공개 생성자 사용 시 기본값 초기화</p>
 */
class CouponEntityBranchTest {

    private Coupon createValidCoupon() {
        return new Coupon("TESTCODE", "테스트 쿠폰",
                DiscountType.FIXED, BigDecimal.valueOf(5000),
                BigDecimal.valueOf(10000), null,
                100, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30));
    }

    // ── update ──

    @Nested
    @DisplayName("update — 쿠폰 정보 갱신")
    class UpdateTests {

        @Test
        @DisplayName("update 호출 시 모든 수정 가능 필드가 갱신된다")
        void update_changesAllMutableFields() {
            // given
            Coupon coupon = createValidCoupon();

            LocalDateTime newFrom = LocalDateTime.of(2025, 6, 1, 0, 0);
            LocalDateTime newUntil = LocalDateTime.of(2025, 12, 31, 23, 59);

            // when: update 호출
            coupon.update("수정된 쿠폰명", DiscountType.PERCENT,
                    BigDecimal.valueOf(15), BigDecimal.valueOf(20000),
                    BigDecimal.valueOf(10000), 200, newFrom, newUntil);

            // then: 모든 필드 갱신 확인
            assertThat(coupon.getCouponName()).isEqualTo("수정된 쿠폰명");
            assertThat(coupon.getDiscountType()).isEqualTo(DiscountType.PERCENT);
            assertThat(coupon.getDiscountValue()).isEqualByComparingTo("15");
            assertThat(coupon.getMinOrderAmount()).isEqualByComparingTo("20000");
            assertThat(coupon.getMaxDiscount()).isEqualByComparingTo("10000");
            assertThat(coupon.getTotalQuantity()).isEqualTo(200);
            assertThat(coupon.getValidFrom()).isEqualTo(newFrom);
            assertThat(coupon.getValidUntil()).isEqualTo(newUntil);

            // 불변 필드는 변경되지 않음
            assertThat(coupon.getCouponCode()).isEqualTo("TESTCODE");
        }
    }

    // ── toggleActive ──

    @Nested
    @DisplayName("toggleActive — 활성/비활성 전환")
    class ToggleActiveTests {

        @Test
        @DisplayName("활성 → 비활성 토글")
        void toggleActive_activeToInactive() {
            Coupon coupon = createValidCoupon();
            assertThat(coupon.getIsActive()).isTrue();

            coupon.toggleActive();

            assertThat(coupon.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("비활성 → 활성 토글")
        void toggleActive_inactiveToActive() {
            Coupon coupon = createValidCoupon();
            coupon.toggleActive(); // true → false
            coupon.toggleActive(); // false → true

            assertThat(coupon.getIsActive()).isTrue();
        }
    }

    // ── isIssuable(LocalDateTime) ──

    @Test
    @DisplayName("isIssuable(now) — 유효 시각 + 수량 여유 → true")
    void isIssuable_withTime_valid_returnsTrue() {
        Coupon coupon = createValidCoupon();
        // 현재 시각은 validFrom~validUntil 사이
        assertThat(coupon.isIssuable(LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("isIssuable(now) — 수량 소진 → false")
    void isIssuable_withTime_exhausted_returnsFalse() {
        Coupon coupon = new Coupon("EX-CODE", "소진 쿠폰",
                DiscountType.FIXED, BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null,
                5, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        // usedQuantity를 totalQuantity까지 증가
        for (int i = 0; i < 5; i++) {
            coupon.incrementUsed();
        }

        assertThat(coupon.isIssuable(LocalDateTime.now())).isFalse();
    }

    // ── isQuantityExhausted: totalQuantity null(무제한) ──

    @Test
    @DisplayName("isQuantityExhausted — totalQuantity null(무제한)이면 false")
    void isQuantityExhausted_unlimited_returnsFalse() {
        Coupon coupon = new Coupon("UNLIM", "무제한",
                DiscountType.FIXED, BigDecimal.valueOf(1000),
                BigDecimal.ZERO, null,
                null, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertThat(coupon.isQuantityExhausted()).isFalse();
    }

    // ── 공개 생성자 기본값 ──

    @Test
    @DisplayName("공개 생성자 — usedQuantity=0, isActive=true, createdAt 초기화")
    void constructor_defaultValues() {
        Coupon coupon = createValidCoupon();

        assertThat(coupon.getUsedQuantity()).isEqualTo(0);
        assertThat(coupon.getIsActive()).isTrue();
        assertThat(coupon.getCreatedAt()).isNotNull();
    }
}

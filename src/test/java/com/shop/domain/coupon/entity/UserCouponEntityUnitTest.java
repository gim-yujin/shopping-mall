package com.shop.domain.coupon.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.shop.domain.coupon.entity.DiscountType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * UserCoupon 엔티티 단위 테스트 — use, cancelUse, isAvailable
 */
class UserCouponEntityUnitTest {

    private Coupon createValidCoupon() throws Exception {
        Coupon coupon = Coupon.class.getDeclaredConstructor().newInstance();
        setField(coupon, "discountType", DiscountType.FIXED);
        setField(coupon, "discountValue", BigDecimal.valueOf(1000));
        setField(coupon, "minOrderAmount", BigDecimal.ZERO);
        setField(coupon, "totalQuantity", 100);
        setField(coupon, "usedQuantity", 10);
        setField(coupon, "isActive", true);
        setField(coupon, "validFrom", LocalDateTime.now().minusDays(1));
        setField(coupon, "validUntil", LocalDateTime.now().plusDays(30));
        return coupon;
    }

    private Coupon createInvalidCoupon() throws Exception {
        Coupon coupon = Coupon.class.getDeclaredConstructor().newInstance();
        setField(coupon, "discountType", DiscountType.FIXED);
        setField(coupon, "discountValue", BigDecimal.valueOf(1000));
        setField(coupon, "minOrderAmount", BigDecimal.ZERO);
        setField(coupon, "totalQuantity", 100);
        setField(coupon, "usedQuantity", 10);
        setField(coupon, "isActive", false); // 비활성
        setField(coupon, "validFrom", LocalDateTime.now().minusDays(1));
        setField(coupon, "validUntil", LocalDateTime.now().plusDays(30));
        return coupon;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== use ====================

    @Test
    @DisplayName("use — 사용 처리 시 isUsed=true, usedAt 기록, orderId 설정")
    void use_setsUsedStateAndOrderId() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createValidCoupon(), LocalDateTime.now().plusDays(30));

        uc.use(100L);

        assertThat(uc.getIsUsed()).isTrue();
        assertThat(uc.getUsedAt()).isNotNull();
        assertThat(uc.getOrderId()).isEqualTo(100L);
    }

    // ==================== cancelUse ====================

    @Test
    @DisplayName("cancelUse — 사용 취소 시 isUsed=false, usedAt=null, orderId=null")
    void cancelUse_resetsUsedState() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createValidCoupon(), LocalDateTime.now().plusDays(30));
        uc.use(100L);

        uc.cancelUse();

        assertThat(uc.getIsUsed()).isFalse();
        assertThat(uc.getUsedAt()).isNull();
        assertThat(uc.getOrderId()).isNull();
    }

    // ==================== isAvailable ====================

    @Test
    @DisplayName("isAvailable — 미사용 + 만료 전 + 유효 쿠폰 → true")
    void isAvailable_allConditionsMet_returnsTrue() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createValidCoupon(), LocalDateTime.now().plusDays(30));
        assertThat(uc.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("isAvailable — 이미 사용됨 → false")
    void isAvailable_alreadyUsed_returnsFalse() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createValidCoupon(), LocalDateTime.now().plusDays(30));
        uc.use(100L);
        assertThat(uc.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable — 만료됨 → false")
    void isAvailable_expired_returnsFalse() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createValidCoupon(), LocalDateTime.now().minusDays(1));
        assertThat(uc.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable — 쿠폰 자체가 비활성 → false")
    void isAvailable_invalidCoupon_returnsFalse() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createInvalidCoupon(), LocalDateTime.now().plusDays(30));
        assertThat(uc.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("use 후 cancelUse → 다시 isAvailable true")
    void useThenCancel_becomesAvailableAgain() throws Exception {
        UserCoupon uc = new UserCoupon(1L, createValidCoupon(), LocalDateTime.now().plusDays(30));
        uc.use(100L);
        assertThat(uc.isAvailable()).isFalse();

        uc.cancelUse();
        assertThat(uc.isAvailable()).isTrue();
    }
}

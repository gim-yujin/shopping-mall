package com.shop.domain.coupon.service;

import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.CouponRepository;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponServiceUnitTest {

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private UserCouponRepository userCouponRepository;

    @InjectMocks
    private CouponService couponService;

    @Test
    @DisplayName("issueCouponById - coupon.isValid()가 false면 INVALID_COUPON 예외")
    void issueCouponById_whenCouponIsInvalid_throwsBusinessException() {
        Long userId = 1L;
        Integer couponId = 10;
        Coupon coupon = mock(Coupon.class);

        when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));
        when(coupon.isValid()).thenReturn(false);

        assertThatThrownBy(() -> couponService.issueCouponById(userId, couponId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("유효하지 않은 쿠폰");

        verify(coupon).isValid();
        verify(couponRepository, never()).incrementUsedQuantityIfAvailable(any());
        verify(userCouponRepository, never()).save(any(UserCoupon.class));
        verify(coupon, never()).incrementUsed();
    }

    @Test
    @DisplayName("issueCouponById - coupon.isValid()가 true면 발급 저장")
    void issueCouponById_whenCouponIsValid_savesUserCoupon() {
        Long userId = 1L;
        Integer couponId = 11;
        Coupon coupon = mock(Coupon.class);
        LocalDateTime validUntil = LocalDateTime.now().plusDays(1);

        when(couponRepository.findById(couponId)).thenReturn(Optional.of(coupon));
        when(coupon.isValid()).thenReturn(true);
        when(coupon.getCouponId()).thenReturn(couponId);
        when(coupon.getValidUntil()).thenReturn(validUntil);
        when(couponRepository.incrementUsedQuantityIfAvailable(couponId)).thenReturn(1);
        when(userCouponRepository.existsByUserIdAndCoupon_CouponId(userId, couponId)).thenReturn(false);

        couponService.issueCouponById(userId, couponId);

        verify(coupon).isValid();
        verify(couponRepository).incrementUsedQuantityIfAvailable(couponId);
        verify(userCouponRepository).save(any(UserCoupon.class));
        verify(coupon, never()).incrementUsed();
    }
}

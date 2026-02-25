package com.shop.domain.coupon.service;

import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.CouponRepository;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final EntityManager entityManager;

    public CouponService(CouponRepository couponRepository, UserCouponRepository userCouponRepository,
                         EntityManager entityManager) {
        this.couponRepository = couponRepository;
        this.userCouponRepository = userCouponRepository;
        this.entityManager = entityManager;
    }

    @Cacheable(value = "activeCoupons", key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)")
    public Page<Coupon> getActiveCoupons(Pageable pageable) {
        return couponRepository.findActiveCoupons(pageable);
    }

    public Page<UserCoupon> getUserCoupons(Long userId, Pageable pageable) {
        return userCouponRepository.findByUserId(userId, pageable);
    }

    public List<UserCoupon> getAvailableCoupons(Long userId) {
        return userCouponRepository.findAvailableCoupons(userId);
    }

    @Transactional
    public void issueCoupon(Long userId, String couponCode) {
        // 비관적 락으로 쿠폰 조회 → 수량 검증 직렬화
        Coupon coupon = couponRepository.findByCouponCodeWithLock(couponCode)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponCode));
        entityManager.refresh(coupon);
        issueToUser(userId, coupon);
    }

    @Transactional
    public void issueCouponById(Long userId, Integer couponId) {
        Coupon coupon = couponRepository.findByIdWithLock(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
        entityManager.refresh(coupon);
        issueToUser(userId, coupon);
    }

    private void issueToUser(Long userId, Coupon coupon) {
        if (!coupon.isValid()) {
            throw new BusinessException("INVALID_COUPON", "유효하지 않은 쿠폰입니다.");
        }
        if (userCouponRepository.existsByUserIdAndCoupon_CouponId(userId, coupon.getCouponId())) {
            throw new BusinessException("ALREADY_ISSUED", "이미 발급받은 쿠폰입니다.");
        }

        coupon.incrementUsed();

        // UNIQUE 제약(uk_user_coupon_user_coupon)으로 동시 중복 발급 방지
        try {
            UserCoupon userCoupon = new UserCoupon(userId, coupon, coupon.getValidUntil());
            userCouponRepository.save(userCoupon);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException("ALREADY_ISSUED", "이미 발급받은 쿠폰입니다.");
        }
    }

    public java.util.Set<Integer> getUserIssuedCouponIds(Long userId) {
        return userCouponRepository.findCouponIdsByUserId(userId);
    }
}

package com.shop.domain.coupon.service;

import com.shop.domain.coupon.dto.AdminCouponRequest;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.CouponRepository;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    public CouponService(CouponRepository couponRepository, UserCouponRepository userCouponRepository) {
        this.couponRepository = couponRepository;
        this.userCouponRepository = userCouponRepository;
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
        Coupon coupon = couponRepository.findByCouponCode(couponCode)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponCode));
        issueToUser(userId, coupon);
    }

    @Transactional
    public void issueCouponById(Long userId, Integer couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
        issueToUser(userId, coupon);
    }

    private void issueToUser(Long userId, Coupon coupon) {
        if (!coupon.isIssuable()) {
            if (coupon.isQuantityExhausted()) {
                throw new BusinessException("COUPON_SOLD_OUT", "쿠폰 수량이 모두 소진되었습니다.");
            }
            throw new BusinessException("INVALID_COUPON", "유효하지 않은 쿠폰입니다.");
        }
        if (userCouponRepository.existsByUserIdAndCoupon_CouponId(userId, coupon.getCouponId())) {
            throw new BusinessException("ALREADY_ISSUED", "이미 발급받은 쿠폰입니다.");
        }

        int updated = couponRepository.incrementUsedQuantityIfAvailable(coupon.getCouponId());
        if (updated == 0) {
            throw new BusinessException("COUPON_SOLD_OUT", "쿠폰 수량이 모두 소진되었습니다.");
        }

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

    // ────────────────────────────────────────────
    // Admin CRUD
    // ────────────────────────────────────────────

    /**
     * 관리자용 전체 쿠폰 목록 (활성/비활성 포함).
     */
    public Page<Coupon> getAllCouponsForAdmin(Pageable pageable) {
        return couponRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * 관리자용 쿠폰 상세 조회.
     */
    public Coupon findByIdForAdmin(Integer couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
    }

    /**
     * 관리자 쿠폰 생성.
     *
     * 유효 기간 검증(validUntil > validFrom)은 DB CHECK 제약(chk_valid_dates)이 강제하지만,
     * 사용자 친화적 에러 메시지를 위해 서비스 레벨에서도 선검증한다.
     * 쿠폰 코드 중복은 UNIQUE 제약이 최종 방어선이며, existsByCouponCode로 선검증하여
     * 의미 있는 에러 메시지를 반환한다.
     */
    @Transactional
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public Coupon createCoupon(AdminCouponRequest request) {
        validateCouponDates(request);
        if (couponRepository.existsByCouponCode(request.getCouponCode())) {
            throw new BusinessException("DUPLICATE_COUPON_CODE", "이미 존재하는 쿠폰 코드입니다.");
        }

        Coupon coupon = new Coupon(
                request.getCouponCode(), request.getCouponName(),
                request.getDiscountType(), request.getDiscountValue(),
                request.getMinOrderAmount(), request.getMaxDiscount(),
                request.getTotalQuantity(),
                request.getValidFrom(), request.getValidUntil()
        );
        return couponRepository.save(coupon);
    }

    /**
     * 관리자 쿠폰 수정.
     *
     * 수정 불가 필드: couponCode(배포된 코드 변경 시 혼란), usedQuantity(트랜잭션에 의해서만 증가).
     * 활성 캐시를 무효화하여 사용자 쿠폰 목록에 변경 사항이 즉시 반영되도록 한다.
     */
    @Transactional
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public Coupon updateCoupon(Integer couponId, AdminCouponRequest request) {
        validateCouponDates(request);
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
        coupon.update(
                request.getCouponName(), request.getDiscountType(),
                request.getDiscountValue(), request.getMinOrderAmount(), request.getMaxDiscount(),
                request.getTotalQuantity(),
                request.getValidFrom(), request.getValidUntil()
        );
        return coupon;
    }

    /**
     * 관리자 쿠폰 활성/비활성 토글.
     *
     * 비활성화해도 이미 발급된 UserCoupon에는 영향을 주지 않는다.
     * 단, 비활성 쿠폰은 신규 발급이 불가하고, 주문 시 사용 가능 쿠폰 목록에서 제외된다.
     * (UserCouponRepository.findAvailableCoupons 쿼리의 c.isActive = true 조건)
     */
    @Transactional
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public void toggleCouponActive(Integer couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
        coupon.toggleActive();
    }

    private void validateCouponDates(AdminCouponRequest request) {
        if (request.getValidUntil().isBefore(request.getValidFrom()) ||
            request.getValidUntil().isEqual(request.getValidFrom())) {
            throw new BusinessException("INVALID_DATES", "유효 종료일은 시작일 이후여야 합니다.");
        }
    }
}

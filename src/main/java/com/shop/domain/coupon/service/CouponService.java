package com.shop.domain.coupon.service;

import com.shop.domain.coupon.dto.AdminCouponRequest;
import com.shop.domain.coupon.dto.CouponStats;
import com.shop.domain.coupon.entity.Coupon;
import com.shop.domain.coupon.entity.UserCoupon;
import com.shop.domain.coupon.repository.CouponRepository;
import com.shop.domain.coupon.repository.UserCouponRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

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
     * [3.11] 대시보드용 쿠폰 통계 집계.
     *
     * [Phase 8] 4개 개별 COUNT 쿼리 → 단일 네이티브 집계 쿼리로 통합.
     *
     * <p><b>문제:</b> 기존 구현은 count(), countActiveCoupons(), count(), countUsedCoupons()를
     * 순차 호출하여 4번의 DB 왕복이 발생했다. 관리자 대시보드는 페이지 로딩마다 이 메서드를
     * 호출하므로, 불필요한 네트워크 왕복이 응답 시간에 직접 영향을 준다.</p>
     *
     * <p><b>해결:</b> CouponRepository.getCouponStatsRaw()가 단일 쿼리에서
     * 4개 집계값을 서브쿼리로 한 번에 계산한다. DB 왕복 4→1회 감소.</p>
     */
    public CouponStats getCouponStats() {
        Object[] row = couponRepository.getCouponStatsRaw();
        return new CouponStats(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue()
        );
    }

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
     *
     * [Phase 4] 낙관적 잠금 충돌 감지.
     *
     * 문제: 두 관리자가 동시에 같은 쿠폰을 수정하면 Lost Update가 발생한다.
     * 해결: Coupon 엔티티의 @Version으로 충돌을 감지하고 의미 있는 에러 메시지를 반환한다.
     */
    @Transactional
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public Coupon updateCoupon(Integer couponId, AdminCouponRequest request) {
        validateCouponDates(request);
        try {
            Coupon coupon = couponRepository.findById(couponId)
                    .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
            coupon.update(
                    request.getCouponName(), request.getDiscountType(),
                    request.getDiscountValue(), request.getMinOrderAmount(), request.getMaxDiscount(),
                    request.getTotalQuantity(),
                    request.getValidFrom(), request.getValidUntil()
            );
            // [Phase 4] 커밋 전 버전 충돌 감지를 위한 명시적 flush
            couponRepository.flush();
            return coupon;
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("쿠폰 수정 중 낙관적 잠금 충돌 - couponId={}", couponId);
            throw new BusinessException("CONCURRENT_MODIFICATION",
                    "다른 관리자에 의해 쿠폰 정보가 변경되었습니다. 페이지를 새로고침 후 다시 시도해주세요.");
        }
    }

    /**
     * 관리자 쿠폰 활성/비활성 토글.
     *
     * 비활성화해도 이미 발급된 UserCoupon에는 영향을 주지 않는다.
     * 단, 비활성 쿠폰은 신규 발급이 불가하고, 주문 시 사용 가능 쿠폰 목록에서 제외된다.
     * (UserCouponRepository.findAvailableCoupons 쿼리의 c.isActive = true 조건)
     *
     * [Phase 4] 낙관적 잠금으로 동시 토글 충돌 감지.
     */
    @Transactional
    @CacheEvict(value = "activeCoupons", allEntries = true)
    public void toggleCouponActive(Integer couponId) {
        try {
            Coupon coupon = couponRepository.findById(couponId)
                    .orElseThrow(() -> new ResourceNotFoundException("쿠폰", couponId));
            coupon.toggleActive();
            // [Phase 4] 커밋 전 버전 충돌 감지를 위한 명시적 flush
            couponRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("쿠폰 활성/비활성 토글 중 낙관적 잠금 충돌 - couponId={}", couponId);
            throw new BusinessException("CONCURRENT_MODIFICATION",
                    "다른 작업에 의해 쿠폰 정보가 변경되었습니다. 페이지를 새로고침 후 다시 시도해주세요.");
        }
    }

    private void validateCouponDates(AdminCouponRequest request) {
        if (request.getValidUntil().isBefore(request.getValidFrom()) ||
            request.getValidUntil().isEqual(request.getValidFrom())) {
            throw new BusinessException("INVALID_DATES", "유효 종료일은 시작일 이후여야 합니다.");
        }
    }
}

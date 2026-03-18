package com.shop.domain.coupon.repository;

import com.shop.domain.coupon.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Integer> {
    Optional<Coupon> findByCouponCode(String couponCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.couponCode = :couponCode")
    Optional<Coupon> findByCouponCodeWithLock(String couponCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE c.couponId = :couponId")
    Optional<Coupon> findByIdWithLock(Integer couponId);

    @Query("SELECT c FROM Coupon c WHERE c.isActive = true AND c.validFrom <= CURRENT_TIMESTAMP AND c.validUntil >= CURRENT_TIMESTAMP ORDER BY c.createdAt DESC")
    Page<Coupon> findActiveCoupons(Pageable pageable);

    /**
     * 관리자용: 전체 쿠폰 목록 (활성/비활성 포함).
     * 최신 생성순 정렬.
     */
    Page<Coupon> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByCouponCode(String couponCode);

    // ────────────────────────────────────────────
    // [3.11] 대시보드 쿠폰 통계 쿼리
    // ────────────────────────────────────────────

    /** 전체 쿠폰 수 */
    long count();

    /** 현재 활성 상태인 쿠폰 수 (isActive=true + 유효기간 내) */
    @Query("SELECT COUNT(c) FROM Coupon c WHERE c.isActive = true " +
           "AND c.validFrom <= CURRENT_TIMESTAMP AND c.validUntil >= CURRENT_TIMESTAMP")
    long countActiveCoupons();

    /** 전체 쿠폰의 총 사용량 합계 */
    @Query("SELECT COALESCE(SUM(c.usedQuantity), 0) FROM Coupon c")
    long sumUsedQuantity();

    // [Phase 8] 쿠폰 통계 단일 집계 쿼리 (4→1 쿼리 통합).
    //
    // 문제: CouponService.getCouponStats()가 4개의 개별 COUNT 쿼리를 실행한다:
    //   1) couponRepository.count()              — 전체 쿠폰 수
    //   2) couponRepository.countActiveCoupons()  — 활성 쿠폰 수
    //   3) userCouponRepository.count()           — 총 발급 수
    //   4) userCouponRepository.countUsedCoupons() — 사용된 쿠폰 수
    // 관리자 대시보드 로딩 시마다 4번의 DB 왕복이 발생하고,
    // 각 쿼리가 독립적으로 실행되어 시점 차이로 인한 통계 불일치가 가능하다.
    //
    // 해결: 단일 네이티브 쿼리에서 coupons와 user_coupons 테이블을 CROSS JOIN하여
    // 4개 집계값을 한 번에 계산한다. DB 왕복 4→1회로 감소하고,
    // 동일 스냅샷 시점의 일관된 통계를 보장한다.
    //
    // 반환: Object[] = [totalCoupons, activeCoupons, totalIssued, totalUsed]
    @Query(value = """
            SELECT
                (SELECT COUNT(*) FROM coupons) AS total_coupons,
                (SELECT COUNT(*) FROM coupons
                 WHERE is_active = true
                   AND valid_from <= NOW()
                   AND valid_until >= NOW()) AS active_coupons,
                (SELECT COUNT(*) FROM user_coupons) AS total_issued,
                (SELECT COUNT(*) FROM user_coupons WHERE is_used = true) AS total_used
            """, nativeQuery = true)
    Object[] getCouponStatsRaw();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Coupon c
            SET c.usedQuantity = c.usedQuantity + 1
            WHERE c.couponId = :couponId
              AND (c.totalQuantity IS NULL OR c.usedQuantity < c.totalQuantity)
            """)
    int incrementUsedQuantityIfAvailable(@Param("couponId") Integer couponId);
}

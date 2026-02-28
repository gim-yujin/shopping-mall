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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Coupon c
            SET c.usedQuantity = c.usedQuantity + 1
            WHERE c.couponId = :couponId
              AND (c.totalQuantity IS NULL OR c.usedQuantity < c.totalQuantity)
            """)
    int incrementUsedQuantityIfAvailable(@Param("couponId") Integer couponId);
}

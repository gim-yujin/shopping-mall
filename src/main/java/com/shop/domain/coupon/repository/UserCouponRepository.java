package com.shop.domain.coupon.repository;

import com.shop.domain.coupon.entity.UserCoupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    @Query("""
            SELECT uc
            FROM UserCoupon uc
            JOIN FETCH uc.coupon c
            WHERE uc.userId = :userId
              AND uc.isUsed = false
              AND uc.expiresAt > CURRENT_TIMESTAMP
              AND c.isActive = true
              AND c.validFrom <= CURRENT_TIMESTAMP
              AND c.validUntil >= CURRENT_TIMESTAMP
              AND (c.totalQuantity IS NULL OR c.usedQuantity < c.totalQuantity)
            ORDER BY uc.expiresAt ASC
            """)
    List<UserCoupon> findAvailableCoupons(@Param("userId") Long userId);

    @Query(value = "SELECT uc FROM UserCoupon uc JOIN FETCH uc.coupon WHERE uc.userId = :userId ORDER BY uc.issuedAt DESC",
           countQuery = "SELECT COUNT(uc) FROM UserCoupon uc WHERE uc.userId = :userId")
    Page<UserCoupon> findByUserId(@Param("userId") Long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT uc FROM UserCoupon uc JOIN FETCH uc.coupon WHERE uc.userCouponId = :userCouponId")
    Optional<UserCoupon> findByIdWithLock(@Param("userCouponId") Long userCouponId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserCoupon uc
            SET uc.isUsed = true,
                uc.usedAt = :usedAt,
                uc.orderId = :orderId
            WHERE uc.userCouponId = :userCouponId
              AND uc.isUsed = false
            """)
    int markAsUsedIfUnused(@Param("userCouponId") Long userCouponId,
                           @Param("orderId") Long orderId,
                           @Param("usedAt") LocalDateTime usedAt);

    Optional<UserCoupon> findByOrderId(Long orderId);

    boolean existsByUserIdAndCoupon_CouponId(Long userId, Integer couponId);

    @Query("SELECT uc.coupon.couponId FROM UserCoupon uc WHERE uc.userId = :userId")
    Set<Integer> findCouponIdsByUserId(@Param("userId") Long userId);
}

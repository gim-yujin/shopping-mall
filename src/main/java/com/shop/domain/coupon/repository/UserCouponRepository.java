package com.shop.domain.coupon.repository;

import com.shop.domain.coupon.entity.UserCoupon;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserCouponRepository extends JpaRepository<UserCoupon, Long> {

    @Query("SELECT uc FROM UserCoupon uc JOIN FETCH uc.coupon WHERE uc.userId = :userId AND uc.isUsed = false AND uc.expiresAt > CURRENT_TIMESTAMP ORDER BY uc.expiresAt ASC")
    List<UserCoupon> findAvailableCoupons(@Param("userId") Long userId);

    @Query(value = "SELECT uc FROM UserCoupon uc JOIN FETCH uc.coupon WHERE uc.userId = :userId ORDER BY uc.issuedAt DESC",
           countQuery = "SELECT COUNT(uc) FROM UserCoupon uc WHERE uc.userId = :userId")
    Page<UserCoupon> findByUserId(@Param("userId") Long userId, Pageable pageable);

    Optional<UserCoupon> findByOrderId(Long orderId);

    boolean existsByUserIdAndCoupon_CouponId(Long userId, Integer couponId);

    @Query("SELECT uc.coupon.couponId FROM UserCoupon uc WHERE uc.userId = :userId")
    Set<Integer> findCouponIdsByUserId(@Param("userId") Long userId);
}

package com.shop.domain.coupon.repository;

import com.shop.domain.coupon.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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
}

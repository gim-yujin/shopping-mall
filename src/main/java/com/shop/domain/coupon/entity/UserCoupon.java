package com.shop.domain.coupon.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_coupons")
public class UserCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_coupon_id")
    private Long userCouponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id", nullable = false)
    private Coupon coupon;

    @Column(name = "is_used", nullable = false)
    private Boolean isUsed;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected UserCoupon() {}

    public UserCoupon(Long userId, Coupon coupon, LocalDateTime expiresAt) {
        this.userId = userId;
        this.coupon = coupon;
        this.isUsed = false;
        this.issuedAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    public void use(Long orderId) {
        this.isUsed = true;
        this.usedAt = LocalDateTime.now();
        this.orderId = orderId;
    }

    public void cancelUse() {
        this.isUsed = false;
        this.usedAt = null;
        this.orderId = null;
    }

    public boolean isAvailable() {
        return isAvailable(LocalDateTime.now());
    }

    /**
     * 주어진 시각 기준으로 쿠폰 사용 가능 여부를 판정한다.
     * 테스트에서 시간을 제어할 수 있도록 시각을 파라미터로 받는다.
     */
    public boolean isAvailable(LocalDateTime now) {
        return !isUsed && now.isBefore(expiresAt) && coupon.isValid(now);
    }

    public Long getUserCouponId() { return userCouponId; }
    public Long getUserId() { return userId; }
    public Coupon getCoupon() { return coupon; }
    public Boolean getIsUsed() { return isUsed; }
    public LocalDateTime getUsedAt() { return usedAt; }
    public Long getOrderId() { return orderId; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}

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
        if (orderId == null) {
            throw new IllegalArgumentException("orderId는 필수입니다.");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("사용할 수 없는 쿠폰입니다.");
        }
        markUsed(orderId);
    }

    public void cancelUse() {
        if (!Boolean.TRUE.equals(this.isUsed)) {
            return;
        }
        markUnused();
    }

    private void markUsed(Long orderId) {
        this.isUsed = true;
        this.usedAt = LocalDateTime.now();
        this.orderId = orderId;
    }

    /**
     * 쿠폰 사용 취소 시 주문 연계를 해제하고 사용 메타데이터를 초기 상태로 되돌린다.
     */
    private void markUnused() {
        this.isUsed = false;
        clearUsedTimestamp();
        detachOrder();
    }

    private void clearUsedTimestamp() {
        this.usedAt = emptyValue();
    }

    private void detachOrder() {
        this.orderId = emptyValue();
    }

    private static <T> T emptyValue() {
        return null;
    }

    public boolean isAvailable() {
        return isAvailable(LocalDateTime.now());
    }

    /**
     * 주어진 시각 기준으로 쿠폰 사용 가능 여부를 판정한다.
     * 테스트에서 시간을 제어할 수 있도록 시각을 파라미터로 받는다.
     */
    public boolean isAvailable(LocalDateTime now) {
        return !Boolean.TRUE.equals(isUsed)
               && now.isBefore(expiresAt)
               && coupon.getIsActive()
               && (now.isAfter(coupon.getValidFrom()) || now.isEqual(coupon.getValidFrom()))
               && (now.isBefore(coupon.getValidUntil()) || now.isEqual(coupon.getValidUntil()));
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

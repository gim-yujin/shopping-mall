package com.shop.domain.coupon.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_id")
    private Integer couponId;

    @Column(name = "coupon_code", unique = true, nullable = false, length = 50)
    private String couponCode;

    @Column(name = "coupon_name", nullable = false, length = 100)
    private String couponName;

    /**
     * [P2-8] String → Enum 전환.
     * @Enumerated(EnumType.STRING)이므로 DB에는 기존과 동일하게 'FIXED', 'PERCENT'로 저장된다.
     * 스키마 변경이나 데이터 마이그레이션이 필요 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderAmount;

    @Column(name = "max_discount", precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "total_quantity")
    private Integer totalQuantity;

    @Column(name = "used_quantity", nullable = false)
    private Integer usedQuantity;

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Coupon() {}

    /**
     * 관리자 쿠폰 생성용 생성자.
     *
     * usedQuantity는 0으로 초기화되며, createdAt은 현재 시각으로 설정된다.
     * DB 스키마의 DEFAULT 값과 동일하지만, JPA persist 시 엔티티 레벨에서도
     * 값을 설정하여 persist 전 검증/로깅에서 null이 나오지 않도록 한다.
     */
    public Coupon(String couponCode, String couponName, DiscountType discountType,
                  BigDecimal discountValue, BigDecimal minOrderAmount, BigDecimal maxDiscount,
                  Integer totalQuantity, LocalDateTime validFrom, LocalDateTime validUntil) {
        this.couponCode = couponCode;
        this.couponName = couponName;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscount = maxDiscount;
        this.totalQuantity = totalQuantity;
        this.usedQuantity = 0;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.isActive = true;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 관리자 쿠폰 수정.
     * couponCode와 usedQuantity는 생성 후 변경하지 않는 불변 필드이다.
     * couponCode: 외부 시스템/사용자에게 이미 배포된 코드는 변경 시 혼란 유발.
     * usedQuantity: 발급/사용 트랜잭션에 의해서만 증가해야 하며, 관리자가 임의 변경 불가.
     */
    public void update(String couponName, DiscountType discountType,
                       BigDecimal discountValue, BigDecimal minOrderAmount, BigDecimal maxDiscount,
                       Integer totalQuantity, LocalDateTime validFrom, LocalDateTime validUntil) {
        this.couponName = couponName;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.minOrderAmount = minOrderAmount;
        this.maxDiscount = maxDiscount;
        this.totalQuantity = totalQuantity;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public void toggleActive() {
        this.isActive = !this.isActive;
    }

    public boolean isQuantityExhausted() {
        return totalQuantity != null && usedQuantity >= totalQuantity;
    }

    public boolean isValid() {
        return isValid(LocalDateTime.now());
    }

    /**
     * 주어진 시각 기준으로 쿠폰이 유효한지 판정한다.
     * 테스트에서 시간을 제어할 수 있도록 시각을 파라미터로 받는다.
     */
    public boolean isValid(LocalDateTime now) {
        return isActive
               && (now.isAfter(validFrom) || now.isEqual(validFrom))
               && (now.isBefore(validUntil) || now.isEqual(validUntil))
               && (totalQuantity == null || usedQuantity < totalQuantity);
    }

    public BigDecimal calculateDiscount(BigDecimal orderAmount) {
        if (orderAmount.compareTo(minOrderAmount) < 0) return BigDecimal.ZERO;
        BigDecimal discount;
        if (discountType == DiscountType.PERCENT) {
            discount = orderAmount.multiply(discountValue).divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.FLOOR);
            if (maxDiscount != null && discount.compareTo(maxDiscount) > 0) {
                discount = maxDiscount;
            }
        } else {
            discount = discountValue;
        }
        return discount;
    }

    public void incrementUsed() { this.usedQuantity++; }

    public Integer getCouponId() { return couponId; }
    public String getCouponCode() { return couponCode; }
    public String getCouponName() { return couponName; }
    public DiscountType getDiscountType() { return discountType; }
    public BigDecimal getDiscountValue() { return discountValue; }
    public BigDecimal getMinOrderAmount() { return minOrderAmount; }
    public BigDecimal getMaxDiscount() { return maxDiscount; }
    public Integer getTotalQuantity() { return totalQuantity; }
    public Integer getUsedQuantity() { return usedQuantity; }
    public LocalDateTime getValidFrom() { return validFrom; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public Boolean getIsActive() { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

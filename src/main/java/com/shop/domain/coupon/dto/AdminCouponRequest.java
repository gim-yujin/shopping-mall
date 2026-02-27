package com.shop.domain.coupon.dto;

import com.shop.domain.coupon.entity.DiscountType;
import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 관리자 쿠폰 생성/수정 요청 DTO.
 *
 * Thymeleaf 폼 바인딩을 위해 기본 생성자 + getter/setter 구조를 사용한다.
 * record가 아닌 이유: th:object 바인딩에 mutable 객체가 필요하고,
 * 수정 폼에서 기존 값을 채워 넣어야 하기 때문이다.
 */
public class AdminCouponRequest {

    @NotBlank(message = "쿠폰 코드를 입력해주세요.")
    @Size(max = 50, message = "쿠폰 코드는 50자 이내로 입력해주세요.")
    private String couponCode;

    @NotBlank(message = "쿠폰명을 입력해주세요.")
    @Size(max = 100, message = "쿠폰명은 100자 이내로 입력해주세요.")
    private String couponName;

    @NotNull(message = "할인 유형을 선택해주세요.")
    private DiscountType discountType;

    @NotNull(message = "할인 값을 입력해주세요.")
    @DecimalMin(value = "0.01", message = "할인 값은 0보다 커야 합니다.")
    private BigDecimal discountValue;

    @NotNull(message = "최소 주문 금액을 입력해주세요.")
    @DecimalMin(value = "0", message = "최소 주문 금액은 0 이상이어야 합니다.")
    private BigDecimal minOrderAmount;

    /** null이면 최대 할인 상한 없음 (정률 할인 시에만 의미 있음) */
    private BigDecimal maxDiscount;

    /** null이면 수량 무제한 */
    @Min(value = 1, message = "발급 수량은 1 이상이어야 합니다.")
    private Integer totalQuantity;

    @NotNull(message = "유효 시작일을 입력해주세요.")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime validFrom;

    @NotNull(message = "유효 종료일을 입력해주세요.")
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime validUntil;

    public AdminCouponRequest() {}

    // ── Getters / Setters ──────────────────────────

    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }

    public String getCouponName() { return couponName; }
    public void setCouponName(String couponName) { this.couponName = couponName; }

    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMinOrderAmount() { return minOrderAmount; }
    public void setMinOrderAmount(BigDecimal minOrderAmount) { this.minOrderAmount = minOrderAmount; }

    public BigDecimal getMaxDiscount() { return maxDiscount; }
    public void setMaxDiscount(BigDecimal maxDiscount) { this.maxDiscount = maxDiscount; }

    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }

    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }

    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
}

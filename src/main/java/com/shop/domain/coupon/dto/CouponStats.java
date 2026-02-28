package com.shop.domain.coupon.dto;

/**
 * [3.11] 대시보드 쿠폰 통계 DTO.
 *
 * 관리자 대시보드에 표시할 쿠폰 관련 집계 데이터를 담는다.
 * - totalCoupons: 등록된 전체 쿠폰 수
 * - activeCoupons: 현재 활성·유효기간 내 쿠폰 수
 * - totalIssued: 사용자에게 발급된 쿠폰 총 수
 * - totalUsed: 실제 사용된 쿠폰 수
 */
public record CouponStats(
    long totalCoupons,
    long activeCoupons,
    long totalIssued,
    long totalUsed
) {
    /**
     * 발급 대비 사용률(%). 발급 0건이면 0을 반환한다.
     */
    public double usageRate() {
        return totalIssued > 0 ? (double) totalUsed / totalIssued * 100 : 0;
    }
}

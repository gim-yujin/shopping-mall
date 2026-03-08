package com.shop.domain.order.service;

import com.shop.domain.user.entity.UserTier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 배송비 및 최종 금액 계산 전담 컴포넌트.
 *
 * OrderService에서 분리: 순수 계산 로직만 담당하며 외부 의존성이 없다.
 * 배송비 정책 변경(지역별, 무게별 등) 시 이 클래스만 수정하면 된다.
 */
@Component
public class ShippingFeeCalculator {

    private static final BigDecimal SHIPPING_FEE_BASE = new BigDecimal("3000");

    /**
     * 등급별 무료배송 기준에 따라 배송비를 계산한다.
     *
     * [FIX] freeShippingThreshold가 null인 경우(BASIC 등급 등) NPE 방어 처리.
     * DB 스키마상 user_tiers.free_shipping_threshold는 nullable 컬럼이다.
     * null은 "해당 등급에 무료배송 혜택이 없음"을 의미하므로,
     * 항상 기본 배송비(3,000원)를 부과하는 것이 올바른 비즈니스 로직이다.
     *
     * 기존 코드는 null.compareTo()를 호출해 NPE가 발생했다.
     * 이 버그는 test-seed.sql에 BASIC 등급(free_shipping_threshold=NULL)의
     * 사용자가 추가된 후 모든 주문 생성 테스트에서 연쇄 실패를 일으켰다.
     *
     * @param tier            사용자 등급 (무료배송 기준 금액 포함)
     * @param itemTotalAmount 상품 합계 금액
     * @return 배송비 (무료배송 조건 충족 시 0, 아니면 기본 배송비)
     */
    public BigDecimal calculateShippingFee(UserTier tier, BigDecimal itemTotalAmount) {
        BigDecimal freeThreshold = tier.getFreeShippingThreshold();

        // freeThreshold가 null이면 무료배송 혜택이 없는 등급 → 기본 배송비 부과
        if (freeThreshold == null) {
            return SHIPPING_FEE_BASE;
        }

        // freeThreshold가 0이면 무조건 무료배송,
        // 주문 금액이 기준 이상이면 무료배송
        if (freeThreshold.compareTo(BigDecimal.ZERO) == 0
                || itemTotalAmount.compareTo(freeThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        return SHIPPING_FEE_BASE;
    }

    /**
     * 상품금액 - 총차감액 + 배송비로 최종 결제 금액을 계산한다.
     * 음수가 되면 0으로 클램핑한다.
     *
     * [P0-3.3 FIX] 파라미터명 totalDiscount → totalDeduction 변경.
     *
     * 기존 문제: 파라미터명이 "totalDiscount"였지만 실제로는
     * 등급할인 + 쿠폰할인 + 포인트사용을 합산한 "총 차감액"이 전달되었다.
     * 수학적으로는 올바르지만, 메서드 시그니처가 오해를 유발했다.
     *
     * 수정: "totalDeduction"으로 이름을 변경하여
     * 할인뿐만 아니라 포인트 사용 등 모든 차감 요소를 포함함을 명확히 한다.
     *
     * @param itemTotalAmount 상품 합계 금액
     * @param totalDeduction  총 차감액 (등급할인 + 쿠폰할인 + 포인트사용)
     * @param shippingFee     배송비
     * @return 최종 결제 금액 (최소 0)
     */
    public BigDecimal calculateFinalAmount(BigDecimal itemTotalAmount, BigDecimal totalDeduction, BigDecimal shippingFee) {
        BigDecimal finalAmount = itemTotalAmount.subtract(totalDeduction).add(shippingFee);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return finalAmount;
    }
}

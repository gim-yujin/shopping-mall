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
     * @param tier            사용자 등급 (무료배송 기준 금액 포함)
     * @param itemTotalAmount 상품 합계 금액
     * @return 배송비 (무료배송 조건 충족 시 0, 아니면 기본 배송비)
     */
    public BigDecimal calculateShippingFee(UserTier tier, BigDecimal itemTotalAmount) {
        BigDecimal freeThreshold = tier.getFreeShippingThreshold();
        if (freeThreshold.compareTo(BigDecimal.ZERO) == 0 || itemTotalAmount.compareTo(freeThreshold) >= 0) {
            return BigDecimal.ZERO;
        }
        return SHIPPING_FEE_BASE;
    }

    /**
     * 상품금액 - 총할인 + 배송비로 최종 결제 금액을 계산한다.
     * 음수가 되면 0으로 클램핑한다.
     */
    public BigDecimal calculateFinalAmount(BigDecimal itemTotalAmount, BigDecimal totalDiscount, BigDecimal shippingFee) {
        BigDecimal finalAmount = itemTotalAmount.subtract(totalDiscount).add(shippingFee);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return finalAmount;
    }
}

package com.shop.domain.order.validation;

import com.shop.domain.order.entity.Order;
import com.shop.global.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 주문 금액/포인트 불변식 사전 검증 유틸.
 *
 * <p>DB CHECK 제약과 동일한 규칙을 애플리케이션 레벨에서도 저장 전에 검증해
 * 더 빠르고 명확한 오류 메시지를 제공한다.</p>
 */
@Component
public class OrderInvariantValidator {

    public void validateBeforePersist(Order order) {
        validateDiscountBreakdown(order);
        validateRefundedAmount(order);
        validateRefundedPoints(order);
    }

    private void validateDiscountBreakdown(Order order) {
        BigDecimal expectedDiscount = order.getTierDiscountAmount().add(order.getCouponDiscountAmount());
        if (order.getDiscountAmount().compareTo(expectedDiscount) != 0) {
            throw new BusinessException(
                    "ORDER_INVARIANT_VIOLATION",
                    "할인 금액 불변식 위반: discount_amount는 tier_discount_amount + coupon_discount_amount와 같아야 합니다."
            );
        }
    }

    private void validateRefundedAmount(Order order) {
        if (order.getRefundedAmount().compareTo(order.getFinalAmount()) > 0) {
            throw new BusinessException(
                    "ORDER_INVARIANT_VIOLATION",
                    "환불 금액 불변식 위반: refunded_amount는 final_amount를 초과할 수 없습니다."
            );
        }
    }

    private void validateRefundedPoints(Order order) {
        if (order.getRefundedPoints() > order.getUsedPoints()) {
            throw new BusinessException(
                    "ORDER_INVARIANT_VIOLATION",
                    "환불 포인트 불변식 위반: refunded_points는 used_points를 초과할 수 없습니다."
            );
        }
    }
}

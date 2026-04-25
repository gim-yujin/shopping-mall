package com.shop.domain.order.validation;

import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderItem;
import com.shop.domain.order.entity.OrderOrigin;
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

    /**
     * [Phase 23-3] 플래시 세일 주문 전용 불변식.
     *
     * <p>설계문서 §2-3·§9-1: 플래시 세일 주문은 일반 주문 경로(쿠폰·포인트·티어할인·배송비)를
     * 모두 우회하므로, 저장 전에 이 우회가 약속대로 이루어졌는지 검증해
     * "플래시 세일 주문이 알고 보니 쿠폰을 먹었다"같은 엣지 회귀를 차단한다.</p>
     *
     * <p>검증 항목:</p>
     * <ul>
     *   <li>단일 OrderItem (multi-item 세일은 MVP 미지원)</li>
     *   <li>쿠폰/티어/총할인 = 0, 사용포인트 = 0, 적립예정 = 0, 배송비 = 0</li>
     *   <li>OrderItem.subtotal == unitPrice × quantity</li>
     *   <li>Order.totalAmount == OrderItem.subtotal == finalAmount</li>
     * </ul>
     */
    public void validateFlashSaleOrder(Order order) {
        if (order.getOrderOrigin() != OrderOrigin.FLASH_SALE) {
            throw flashInvariant("order_origin must be FLASH_SALE");
        }
        if (order.getItems() == null || order.getItems().size() != 1) {
            throw new BusinessException(
                    "FLASH_SALE_INVARIANT_VIOLATION",
                    "플래시 세일 주문은 정확히 1개의 라인을 가져야 합니다.");
        }
        OrderItem line = order.getItems().get(0);

        requireZero(order.getDiscountAmount(), "discount_amount");
        requireZero(order.getTierDiscountAmount(), "tier_discount_amount");
        requireZero(order.getCouponDiscountAmount(), "coupon_discount_amount");
        requireZero(order.getShippingFee(), "shipping_fee");
        if (order.getUsedPoints() != null && order.getUsedPoints() != 0) {
            throw flashInvariant("used_points must be 0");
        }
        if (order.getEarnedPointsSnapshot() != null && order.getEarnedPointsSnapshot() != 0) {
            throw flashInvariant("earned_points_snapshot must be 0");
        }

        BigDecimal expectedLineSubtotal = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
        if (line.getSubtotal().compareTo(expectedLineSubtotal) != 0) {
            throw flashInvariant("line subtotal must equal unit_price × quantity");
        }
        if (order.getTotalAmount().compareTo(line.getSubtotal()) != 0) {
            throw flashInvariant("total_amount must equal line subtotal");
        }
        if (order.getFinalAmount().compareTo(order.getTotalAmount()) != 0) {
            throw flashInvariant("final_amount must equal total_amount (no shipping/discount)");
        }
    }

    private void requireZero(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) != 0) {
            throw flashInvariant(field + " must be 0");
        }
    }

    private BusinessException flashInvariant(String detail) {
        return new BusinessException(
                "FLASH_SALE_INVARIANT_VIOLATION",
                "플래시 세일 주문 불변식 위반: " + detail);
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

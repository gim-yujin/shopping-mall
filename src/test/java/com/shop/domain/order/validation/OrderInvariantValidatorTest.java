package com.shop.domain.order.validation;

import com.shop.domain.order.entity.Order;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OrderInvariantValidator 단위 테스트.
 *
 * DB CHECK 제약과 동일한 규칙을 애플리케이션 레벨에서 사전 검증하는 유틸의 테스트.
 * 순수 계산 로직이므로 Spring 컨텍스트 없이, Order의 getter만 Mock하여 검증한다.
 *
 * 검증 대상 불변식 3가지:
 * 1. discount_amount == tier_discount_amount + coupon_discount_amount
 * 2. refunded_amount <= final_amount
 * 3. refunded_points <= used_points
 */
class OrderInvariantValidatorTest {

    private OrderInvariantValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrderInvariantValidator();
    }

    /**
     * Order Mock을 생성하는 헬퍼.
     * 기본값: 모든 불변식을 만족하는 정상 주문.
     */
    private Order createValidOrderMock() {
        Order order = mock(Order.class);
        // 할인 불변식: 1000(등급) + 2000(쿠폰) = 3000(총 할인)
        when(order.getTierDiscountAmount()).thenReturn(new BigDecimal("1000"));
        when(order.getCouponDiscountAmount()).thenReturn(new BigDecimal("2000"));
        when(order.getDiscountAmount()).thenReturn(new BigDecimal("3000"));
        // 환불 금액 불변식: 환불 5000 <= 최종 10000
        when(order.getRefundedAmount()).thenReturn(new BigDecimal("5000"));
        when(order.getFinalAmount()).thenReturn(new BigDecimal("10000"));
        // 환불 포인트 불변식: 환불 50P <= 사용 100P
        when(order.getRefundedPoints()).thenReturn(50);
        when(order.getUsedPoints()).thenReturn(100);
        return order;
    }

    @Test
    @DisplayName("모든 불변식을 만족하는 주문 → 예외 없음")
    void validateBeforePersist_validOrder_noException() {
        // given: 할인, 환불 금액, 환불 포인트 모두 정상 범위
        Order order = createValidOrderMock();

        // when & then: 예외가 발생하지 않아야 함
        assertThatCode(() -> validator.validateBeforePersist(order))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("경계값: 환불 금액이 최종 금액과 정확히 같을 때 → 통과")
    void validateBeforePersist_refundedEqualsToFinal_passes() {
        // given: refundedAmount == finalAmount (전액 환불)
        Order order = createValidOrderMock();
        when(order.getRefundedAmount()).thenReturn(new BigDecimal("10000"));
        when(order.getFinalAmount()).thenReturn(new BigDecimal("10000"));

        // when & then
        assertThatCode(() -> validator.validateBeforePersist(order))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("경계값: 환불 포인트가 사용 포인트와 정확히 같을 때 → 통과")
    void validateBeforePersist_refundedPointsEqualsUsed_passes() {
        // given: refundedPoints == usedPoints (전액 환불)
        Order order = createValidOrderMock();
        when(order.getRefundedPoints()).thenReturn(100);
        when(order.getUsedPoints()).thenReturn(100);

        // when & then
        assertThatCode(() -> validator.validateBeforePersist(order))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("할인 금액 불변식 위반 → ORDER_INVARIANT_VIOLATION 예외")
    void validateBeforePersist_discountBreakdownMismatch_throwsException() {
        // given: discount(3000) ≠ tier(1000) + coupon(1500) = 2500
        // 이 불일치는 코드 버그나 데이터 조작을 의미한다.
        Order order = createValidOrderMock();
        when(order.getCouponDiscountAmount()).thenReturn(new BigDecimal("1500"));

        // when & then
        assertThatThrownBy(() -> validator.validateBeforePersist(order))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_INVARIANT_VIOLATION")
                .hasMessageContaining("할인 금액 불변식 위반");
    }

    @Test
    @DisplayName("환불 금액 초과 불변식 위반 → ORDER_INVARIANT_VIOLATION 예외")
    void validateBeforePersist_refundedExceedsFinal_throwsException() {
        // given: refundedAmount(15000) > finalAmount(10000)
        // 최종 결제 금액보다 많이 환불하려는 시도는 차단해야 한다.
        Order order = createValidOrderMock();
        when(order.getRefundedAmount()).thenReturn(new BigDecimal("15000"));
        when(order.getFinalAmount()).thenReturn(new BigDecimal("10000"));

        // when & then
        assertThatThrownBy(() -> validator.validateBeforePersist(order))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_INVARIANT_VIOLATION")
                .hasMessageContaining("환불 금액 불변식 위반");
    }

    @Test
    @DisplayName("환불 포인트 초과 불변식 위반 → ORDER_INVARIANT_VIOLATION 예외")
    void validateBeforePersist_refundedPointsExceedUsed_throwsException() {
        // given: refundedPoints(150) > usedPoints(100)
        // 사용한 포인트보다 많이 환불하는 것은 부당 포인트 지급이 된다.
        Order order = createValidOrderMock();
        when(order.getRefundedPoints()).thenReturn(150);
        when(order.getUsedPoints()).thenReturn(100);

        // when & then
        assertThatThrownBy(() -> validator.validateBeforePersist(order))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_INVARIANT_VIOLATION")
                .hasMessageContaining("환불 포인트 불변식 위반");
    }
}

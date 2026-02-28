package com.shop.domain.order.entity;

import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/**
 * Step 1 단위 테스트: OrderItemStatus 상태 전이 + OrderItem 엔티티 상태 전이 메서드.
 *
 * <h3>테스트 범위</h3>
 * <ul>
 *   <li>OrderItemStatus.canTransitionTo() — 모든 허용/불허 전이 조합 검증</li>
 *   <li>OrderItem.requestReturn() — NORMAL → RETURN_REQUESTED 전이 + 필드 갱신</li>
 *   <li>OrderItem.approveReturn() — RETURN_REQUESTED → RETURNED 전이 + 수량/금액 확정</li>
 *   <li>OrderItem.rejectReturn() — RETURN_REQUESTED → RETURN_REJECTED 전이 + 대기 수량 원복</li>
 *   <li>OrderItem.applyPartialCancel() — 잔여 수량 0이면 CANCELLED 전이</li>
 *   <li>OrderItem.getRemainingQuantity() — pendingReturnQuantity 포함 계산</li>
 *   <li>재신청 플로우: RETURN_REJECTED → RETURN_REQUESTED</li>
 * </ul>
 */
class OrderItemStatusTest {

    // ═══════════════════════════════════════════════════════════
    // 1. OrderItemStatus enum 상태 전이 규칙 테스트
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("OrderItemStatus — 허용 전이")
    class AllowedTransitions {

        @Test
        @DisplayName("NORMAL → RETURN_REQUESTED: 사용자 반품 신청")
        void normal_to_returnRequested() {
            assertThat(OrderItemStatus.NORMAL.canTransitionTo(OrderItemStatus.RETURN_REQUESTED))
                    .isTrue();
        }

        @Test
        @DisplayName("NORMAL → CANCELLED: 사용자 부분 취소")
        void normal_to_cancelled() {
            assertThat(OrderItemStatus.NORMAL.canTransitionTo(OrderItemStatus.CANCELLED))
                    .isTrue();
        }

        @Test
        @DisplayName("RETURN_REQUESTED → RETURN_APPROVED: 관리자 승인")
        void returnRequested_to_returnApproved() {
            assertThat(OrderItemStatus.RETURN_REQUESTED.canTransitionTo(OrderItemStatus.RETURN_APPROVED))
                    .isTrue();
        }

        @Test
        @DisplayName("RETURN_REQUESTED → RETURN_REJECTED: 관리자 거절")
        void returnRequested_to_returnRejected() {
            assertThat(OrderItemStatus.RETURN_REQUESTED.canTransitionTo(OrderItemStatus.RETURN_REJECTED))
                    .isTrue();
        }

        @Test
        @DisplayName("RETURN_APPROVED → RETURNED: 승인 후 반품 완료")
        void returnApproved_to_returned() {
            assertThat(OrderItemStatus.RETURN_APPROVED.canTransitionTo(OrderItemStatus.RETURNED))
                    .isTrue();
        }

        @Test
        @DisplayName("RETURN_REJECTED → RETURN_REQUESTED: 거절 후 재신청 허용")
        void returnRejected_to_returnRequested() {
            assertThat(OrderItemStatus.RETURN_REJECTED.canTransitionTo(OrderItemStatus.RETURN_REQUESTED))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("OrderItemStatus — 불허 전이")
    class ForbiddenTransitions {

        @Test
        @DisplayName("NORMAL → RETURNED: 건너뛰기 불가 (신청→승인 단계를 거쳐야 함)")
        void normal_to_returned_forbidden() {
            assertThat(OrderItemStatus.NORMAL.canTransitionTo(OrderItemStatus.RETURNED))
                    .isFalse();
        }

        @Test
        @DisplayName("NORMAL → RETURN_APPROVED: 건너뛰기 불가")
        void normal_to_returnApproved_forbidden() {
            assertThat(OrderItemStatus.NORMAL.canTransitionTo(OrderItemStatus.RETURN_APPROVED))
                    .isFalse();
        }

        @Test
        @DisplayName("NORMAL → RETURN_REJECTED: 신청 없이 거절 불가")
        void normal_to_returnRejected_forbidden() {
            assertThat(OrderItemStatus.NORMAL.canTransitionTo(OrderItemStatus.RETURN_REJECTED))
                    .isFalse();
        }

        @Test
        @DisplayName("RETURN_REQUESTED → RETURNED: 승인 단계 건너뛰기 불가")
        void returnRequested_to_returned_forbidden() {
            assertThat(OrderItemStatus.RETURN_REQUESTED.canTransitionTo(OrderItemStatus.RETURNED))
                    .isFalse();
        }

        @Test
        @DisplayName("RETURN_REQUESTED → CANCELLED: 반품 신청 중 취소 불가")
        void returnRequested_to_cancelled_forbidden() {
            assertThat(OrderItemStatus.RETURN_REQUESTED.canTransitionTo(OrderItemStatus.CANCELLED))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("OrderItemStatus — 종결 상태 (RETURNED, CANCELLED)")
    class TerminalStates {

        @ParameterizedTest(name = "RETURNED → {0}: 종결 상태에서 전이 불가")
        @EnumSource(OrderItemStatus.class)
        @DisplayName("RETURNED에서 모든 상태로의 전이가 차단된다")
        void returned_cannotTransitionToAny(OrderItemStatus target) {
            assertThat(OrderItemStatus.RETURNED.canTransitionTo(target))
                    .isFalse();
        }

        @ParameterizedTest(name = "CANCELLED → {0}: 종결 상태에서 전이 불가")
        @EnumSource(OrderItemStatus.class)
        @DisplayName("CANCELLED에서 모든 상태로의 전이가 차단된다")
        void cancelled_cannotTransitionToAny(OrderItemStatus target) {
            assertThat(OrderItemStatus.CANCELLED.canTransitionTo(target))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("OrderItemStatus — label/badgeClass")
    class EnumProperties {

        @Test
        @DisplayName("모든 상태에 label과 badgeClass가 설정되어 있다")
        void allStatuses_haveLabelAndBadgeClass() {
            for (OrderItemStatus status : OrderItemStatus.values()) {
                assertThat(status.getLabel()).isNotBlank();
                assertThat(status.getBadgeClass()).isNotBlank();
            }
        }

        @Test
        @DisplayName("RETURN_REQUESTED의 label은 '반품신청'이다")
        void returnRequested_label() {
            assertThat(OrderItemStatus.RETURN_REQUESTED.getLabel()).isEqualTo("반품신청");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 2. OrderItem 엔티티 상태 전이 메서드 테스트
    // ═══════════════════════════════════════════════════════════

    /**
     * 테스트용 OrderItem을 생성한다.
     * 상품 5개, 단가 10,000원, 소계 50,000원.
     */
    private OrderItem createTestItem() {
        return new OrderItem(
                1L, "테스트 상품", 5,
                new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("50000"));
    }

    @Nested
    @DisplayName("OrderItem.requestReturn — 반품 신청")
    class RequestReturn {

        @Test
        @DisplayName("NORMAL 상태에서 반품 신청이 성공한다")
        void normal_requestReturn_succeeds() {
            OrderItem item = createTestItem();

            item.requestReturn(2, "DEFECT");

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_REQUESTED);
            assertThat(item.getReturnReason()).isEqualTo("DEFECT");
            assertThat(item.getPendingReturnQuantity()).isEqualTo(2);
            assertThat(item.getReturnRequestedAt()).isNotNull();
            // 환불은 아직 발생하지 않음
            assertThat(item.getReturnedQuantity()).isZero();
            assertThat(item.getReturnedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("반품 신청 시 잔여 수량이 pendingReturnQuantity만큼 감소한다")
        void requestReturn_reducesRemainingQuantity() {
            OrderItem item = createTestItem();  // quantity=5, remaining=5

            item.requestReturn(2, "WRONG_ITEM");

            // remaining = 5 - 0(cancelled) - 0(returned) - 2(pending) = 3
            assertThat(item.getRemainingQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("RETURN_REQUESTED 상태에서 중복 반품 신청 시 예외 발생")
        void returnRequested_requestReturn_throwsException() {
            OrderItem item = createTestItem();
            item.requestReturn(2, "DEFECT");

            assertThatThrownBy(() -> item.requestReturn(1, "OTHER"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("CANCELLED 상태에서 반품 신청 시 예외 발생")
        void cancelled_requestReturn_throwsException() {
            OrderItem item = createTestItem();
            // 전량 취소로 CANCELLED 상태 만들기
            item.applyPartialCancel(5, new BigDecimal("50000"));
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);

            assertThatThrownBy(() -> item.requestReturn(1, "DEFECT"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("RETURNED 상태에서 반품 신청 시 예외 발생")
        void returned_requestReturn_throwsException() {
            OrderItem item = createTestItem();
            item.requestReturn(5, "DEFECT");
            item.approveReturn(5, new BigDecimal("50000"));
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);

            assertThatThrownBy(() -> item.requestReturn(1, "OTHER"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS_TRANSITION");
        }
    }

    @Nested
    @DisplayName("OrderItem.approveReturn — 관리자 반품 승인")
    class ApproveReturn {

        @Test
        @DisplayName("RETURN_REQUESTED 상태에서 승인이 성공하고 RETURNED로 전이한다")
        void returnRequested_approve_succeeds() {
            OrderItem item = createTestItem();
            item.requestReturn(2, "DEFECT");

            item.approveReturn(2, new BigDecimal("20000"));

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
            assertThat(item.getReturnedQuantity()).isEqualTo(2);
            assertThat(item.getReturnedAmount()).isEqualByComparingTo(new BigDecimal("20000"));
            assertThat(item.getPendingReturnQuantity()).isZero();
            assertThat(item.getReturnedAt()).isNotNull();
        }

        @Test
        @DisplayName("승인 후 잔여 수량이 정확하게 계산된다")
        void approveReturn_updatesRemainingQuantity() {
            OrderItem item = createTestItem();  // quantity=5
            item.requestReturn(2, "DEFECT");
            // pending 상태: remaining = 5 - 0 - 0 - 2 = 3

            item.approveReturn(2, new BigDecimal("20000"));
            // 승인 후: remaining = 5 - 0 - 2(returned) - 0(pending) = 3
            assertThat(item.getRemainingQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("NORMAL 상태에서 승인 시 예외 발생 (신청 단계를 거쳐야 함)")
        void normal_approve_throwsException() {
            OrderItem item = createTestItem();

            assertThatThrownBy(() -> item.approveReturn(2, new BigDecimal("20000")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS_TRANSITION");
        }

        @Test
        @DisplayName("RETURN_REJECTED 상태에서 직접 승인 시 예외 발생 (재신청을 먼저 해야 함)")
        void returnRejected_approve_throwsException() {
            OrderItem item = createTestItem();
            item.requestReturn(2, "DEFECT");
            item.rejectReturn("상품 확인 불가");

            assertThatThrownBy(() -> item.approveReturn(2, new BigDecimal("20000")))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS_TRANSITION");
        }
    }

    @Nested
    @DisplayName("OrderItem.rejectReturn — 관리자 반품 거절")
    class RejectReturn {

        @Test
        @DisplayName("RETURN_REQUESTED 상태에서 거절이 성공한다")
        void returnRequested_reject_succeeds() {
            OrderItem item = createTestItem();
            item.requestReturn(2, "CHANGE_OF_MIND");

            item.rejectReturn("반품 기간 내 상품 훼손 확인");

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_REJECTED);
            assertThat(item.getRejectReason()).isEqualTo("반품 기간 내 상품 훼손 확인");
        }

        @Test
        @DisplayName("거절 시 pendingReturnQuantity가 0으로 원복된다 (잔여 수량 복원)")
        void rejectReturn_restoresPendingQuantity() {
            OrderItem item = createTestItem();  // quantity=5, remaining=5
            item.requestReturn(2, "DEFECT");
            assertThat(item.getRemainingQuantity()).isEqualTo(3);  // pending 차감

            item.rejectReturn("사유 불충분");

            assertThat(item.getPendingReturnQuantity()).isZero();
            assertThat(item.getRemainingQuantity()).isEqualTo(5);  // 원복
        }

        @Test
        @DisplayName("거절 시 재고/환불 변경이 없다")
        void rejectReturn_noRefundChanges() {
            OrderItem item = createTestItem();
            item.requestReturn(2, "DEFECT");

            item.rejectReturn("검수 불합격");

            assertThat(item.getReturnedQuantity()).isZero();
            assertThat(item.getReturnedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("NORMAL 상태에서 거절 시 예외 발생")
        void normal_reject_throwsException() {
            OrderItem item = createTestItem();

            assertThatThrownBy(() -> item.rejectReturn("사유"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_ITEM_STATUS_TRANSITION");
        }
    }

    @Nested
    @DisplayName("재신청 플로우: RETURN_REJECTED → RETURN_REQUESTED")
    class ReapplyAfterRejection {

        @Test
        @DisplayName("거절 후 재신청이 성공한다")
        void reapply_afterRejection_succeeds() {
            OrderItem item = createTestItem();
            // 1차 신청 → 거절
            item.requestReturn(2, "DEFECT");
            item.rejectReturn("증빙 부족");
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_REJECTED);

            // 2차 재신청
            item.requestReturn(2, "DEFECT");

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_REQUESTED);
            assertThat(item.getPendingReturnQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("거절 → 재신청 → 승인 전체 플로우가 정상 동작한다")
        void fullFlow_rejectThenReapplyThenApprove() {
            OrderItem item = createTestItem();

            // 1차: 신청 → 거절
            item.requestReturn(2, "DEFECT");
            item.rejectReturn("증빙 부족");

            // 2차: 재신청 → 승인
            item.requestReturn(2, "DEFECT");
            item.approveReturn(2, new BigDecimal("20000"));

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURNED);
            assertThat(item.getReturnedQuantity()).isEqualTo(2);
            assertThat(item.getReturnedAmount()).isEqualByComparingTo(new BigDecimal("20000"));
            assertThat(item.getPendingReturnQuantity()).isZero();
            assertThat(item.getRemainingQuantity()).isEqualTo(3);  // 5 - 0 - 2 - 0
        }
    }

    @Nested
    @DisplayName("OrderItem.applyPartialCancel — 부분 취소 + 상태 전이")
    class ApplyPartialCancel {

        @Test
        @DisplayName("부분 취소 후 잔여 수량이 남으면 NORMAL 상태를 유지한다")
        void partialCancel_withRemaining_staysNormal() {
            OrderItem item = createTestItem();  // quantity=5

            item.applyPartialCancel(2, new BigDecimal("20000"));

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
            assertThat(item.getCancelledQuantity()).isEqualTo(2);
            assertThat(item.getRemainingQuantity()).isEqualTo(3);
        }

        @Test
        @DisplayName("전량 취소 시 CANCELLED로 전이한다")
        void fullCancel_transitionsToCancelled() {
            OrderItem item = createTestItem();  // quantity=5

            item.applyPartialCancel(5, new BigDecimal("50000"));

            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
            assertThat(item.getRemainingQuantity()).isZero();
        }

        @Test
        @DisplayName("분할 취소(2회)로 잔여 수량이 0이 되면 CANCELLED로 전이한다")
        void splitCancel_eventuallyTransitionsToCancelled() {
            OrderItem item = createTestItem();  // quantity=5

            item.applyPartialCancel(3, new BigDecimal("30000"));
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);

            item.applyPartialCancel(2, new BigDecimal("20000"));
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
            assertThat(item.getRemainingQuantity()).isZero();
        }
    }

    @Nested
    @DisplayName("getRemainingQuantity — pendingReturnQuantity 포함 계산")
    class RemainingQuantity {

        @Test
        @DisplayName("초기 상태: 잔여 수량 = 주문 수량")
        void initial_remainingEqualsQuantity() {
            OrderItem item = createTestItem();  // quantity=5

            assertThat(item.getRemainingQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("취소 + 반품 완료 + 반품 대기가 모두 반영된다")
        void allDeductions_areReflected() {
            OrderItem item = createTestItem();  // quantity=5

            // 1개 부분 취소
            item.applyPartialCancel(1, new BigDecimal("10000"));
            assertThat(item.getRemainingQuantity()).isEqualTo(4);

            // 2개 반품 신청 (pending)
            item.requestReturn(2, "DEFECT");
            // remaining = 5 - 1(cancelled) - 0(returned) - 2(pending) = 2
            assertThat(item.getRemainingQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("반품 승인 후 잔여 수량이 올바르게 계산된다")
        void afterApproval_remainingIsCorrect() {
            OrderItem item = createTestItem();  // quantity=5
            item.applyPartialCancel(1, new BigDecimal("10000"));  // cancelled=1
            // 기존 반품 신청은 NORMAL에서만 가능하므로, 부분취소 후 아직 NORMAL
            item.requestReturn(2, "DEFECT");  // pending=2
            item.approveReturn(2, new BigDecimal("20000"));  // returned=2, pending=0

            // remaining = 5 - 1(cancelled) - 2(returned) - 0(pending) = 2
            assertThat(item.getRemainingQuantity()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("기존 applyReturn 호환성 (Step 2에서 대체 예정)")
    class LegacyApplyReturn {

        @Test
        @DisplayName("applyReturn은 상태를 변경하지 않는다 (기존 동작 유지)")
        void applyReturn_doesNotChangeStatus() {
            OrderItem item = createTestItem();

            item.applyReturn(2, new BigDecimal("20000"));

            // applyReturn은 상태 전이 없이 수량/금액만 갱신 (기존 호환)
            assertThat(item.getStatus()).isEqualTo(OrderItemStatus.NORMAL);
            assertThat(item.getReturnedQuantity()).isEqualTo(2);
            assertThat(item.getReturnedAmount()).isEqualByComparingTo(new BigDecimal("20000"));
        }
    }
}

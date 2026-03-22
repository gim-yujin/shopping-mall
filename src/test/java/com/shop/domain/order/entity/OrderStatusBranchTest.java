package com.shop.domain.order.entity;

import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderStatus 분기 커버리지 보강 테스트.
 *
 * <p>기존 OrderEntityUnitTest에서 다루지 않은 분기를 검증한다:
 * - canTransitionTo: PENDING, PAID, SHIPPED, DELIVERED, CANCELLED의 모든 전이 조합
 * - from: null/blank 입력, 유효/무효 문자열
 * - fromOrThrow: 잘못된 상태 문자열 → BusinessException
 * - getProgressIndex: 각 상태별 진행 인덱스
 * - codes, labelsByCode, badgeClassesByCode: 유틸리티 메서드</p>
 */
class OrderStatusBranchTest {

    // ── canTransitionTo: PENDING 분기 ──

    @Nested
    @DisplayName("canTransitionTo — PENDING 상태")
    class PendingTransitions {

        @Test
        @DisplayName("PENDING → PENDING: 허용 (멱등성)")
        void pending_toPending_allowed() {
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PENDING)).isTrue();
        }

        @Test
        @DisplayName("PENDING → PAID: 허용")
        void pending_toPaid_allowed() {
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAID)).isTrue();
        }

        @Test
        @DisplayName("PENDING → CANCELLED: 허용")
        void pending_toCancelled_allowed() {
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("PENDING → SHIPPED: 불허 (결제 없이 배송 불가)")
        void pending_toShipped_forbidden() {
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.SHIPPED)).isFalse();
        }

        @Test
        @DisplayName("PENDING → DELIVERED: 불허")
        void pending_toDelivered_forbidden() {
            assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
        }
    }

    // ── canTransitionTo: PAID 분기 ──

    @Nested
    @DisplayName("canTransitionTo — PAID 상태")
    class PaidTransitions {

        @Test
        @DisplayName("PAID → PAID: 허용 (멱등성)")
        void paid_toPaid_allowed() {
            assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PAID)).isTrue();
        }

        @Test
        @DisplayName("PAID → SHIPPED: 허용")
        void paid_toShipped_allowed() {
            assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        }

        @Test
        @DisplayName("PAID → CANCELLED: 허용")
        void paid_toCancelled_allowed() {
            assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        }

        @Test
        @DisplayName("PAID → PENDING: 불허 (역방향 전이 불가)")
        void paid_toPending_forbidden() {
            assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PENDING)).isFalse();
        }

        @Test
        @DisplayName("PAID → DELIVERED: 불허 (배송 단계 건너뛰기 불가)")
        void paid_toDelivered_forbidden() {
            assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.DELIVERED)).isFalse();
        }
    }

    // ── canTransitionTo: SHIPPED 분기 ──

    @Nested
    @DisplayName("canTransitionTo — SHIPPED 상태")
    class ShippedTransitions {

        @Test
        @DisplayName("SHIPPED → SHIPPED: 허용 (멱등성)")
        void shipped_toShipped_allowed() {
            assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
        }

        @Test
        @DisplayName("SHIPPED → DELIVERED: 허용")
        void shipped_toDelivered_allowed() {
            assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
        }

        @Test
        @DisplayName("SHIPPED → CANCELLED: 불허 (배송 중 취소 불가)")
        void shipped_toCancelled_forbidden() {
            assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("SHIPPED → PENDING: 불허")
        void shipped_toPending_forbidden() {
            assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.PENDING)).isFalse();
        }
    }

    // ── canTransitionTo: CANCELLED 분기 ──

    @Nested
    @DisplayName("canTransitionTo — CANCELLED (종결 상태)")
    class CancelledTransitions {

        @Test
        @DisplayName("CANCELLED → CANCELLED: 허용 (멱등성)")
        void cancelled_toCancelled_allowed() {
            assertThat(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
        }

        @ParameterizedTest(name = "CANCELLED → {0}: 다른 상태로 전이 불가")
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID", "SHIPPED", "DELIVERED"})
        @DisplayName("CANCELLED에서 자기 자신 외 전이 불가")
        void cancelled_toOthers_forbidden(OrderStatus target) {
            assertThat(OrderStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }

    // ── canTransitionTo: DELIVERED 분기 ──

    @Nested
    @DisplayName("canTransitionTo — DELIVERED (종결 상태)")
    class DeliveredTransitions {

        @Test
        @DisplayName("DELIVERED → DELIVERED: 허용 (멱등성)")
        void delivered_toDelivered_allowed() {
            assertThat(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
        }

        @ParameterizedTest(name = "DELIVERED → {0}: 다른 상태로 전이 불가")
        @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAID", "SHIPPED", "CANCELLED"})
        @DisplayName("DELIVERED에서 자기 자신 외 전이 불가")
        void delivered_toOthers_forbidden(OrderStatus target) {
            assertThat(OrderStatus.DELIVERED.canTransitionTo(target)).isFalse();
        }
    }

    // ── from: null/blank/유효/무효 입력 ──

    @Nested
    @DisplayName("from — 문자열 → OrderStatus 변환")
    class FromTests {

        @Test
        @DisplayName("null 입력 → Optional.empty()")
        void from_null_returnsEmpty() {
            assertThat(OrderStatus.from(null)).isEmpty();
        }

        @Test
        @DisplayName("빈 문자열 → Optional.empty()")
        void from_empty_returnsEmpty() {
            assertThat(OrderStatus.from("")).isEmpty();
        }

        @Test
        @DisplayName("공백 문자열 → Optional.empty()")
        void from_blank_returnsEmpty() {
            assertThat(OrderStatus.from("   ")).isEmpty();
        }

        @Test
        @DisplayName("유효 문자열(대문자) → 해당 상태 반환")
        void from_validUpperCase_returnsStatus() {
            assertThat(OrderStatus.from("PENDING")).contains(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("유효 문자열(소문자) → 대소문자 무시하여 반환")
        void from_validLowerCase_returnsStatus() {
            assertThat(OrderStatus.from("paid")).contains(OrderStatus.PAID);
        }

        @Test
        @DisplayName("앞뒤 공백 포함 → trim 후 매칭")
        void from_withWhitespace_trimsAndMatches() {
            assertThat(OrderStatus.from("  SHIPPED  ")).contains(OrderStatus.SHIPPED);
        }

        @Test
        @DisplayName("무효 문자열 → Optional.empty()")
        void from_invalid_returnsEmpty() {
            assertThat(OrderStatus.from("INVALID_STATUS")).isEmpty();
        }
    }

    // ── fromOrThrow ──

    @Nested
    @DisplayName("fromOrThrow — 실패 시 예외")
    class FromOrThrowTests {

        @Test
        @DisplayName("유효 상태 → 정상 반환")
        void fromOrThrow_valid_returnsStatus() {
            assertThat(OrderStatus.fromOrThrow("DELIVERED")).isEqualTo(OrderStatus.DELIVERED);
        }

        @Test
        @DisplayName("무효 상태 → BusinessException")
        void fromOrThrow_invalid_throwsException() {
            assertThatThrownBy(() -> OrderStatus.fromOrThrow("UNKNOWN"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ── getProgressIndex ──

    @Test
    @DisplayName("getProgressIndex — 각 상태별 진행 인덱스")
    void getProgressIndex_correctValues() {
        assertThat(OrderStatus.PENDING.getProgressIndex()).isEqualTo(0);
        assertThat(OrderStatus.PAID.getProgressIndex()).isEqualTo(1);
        assertThat(OrderStatus.SHIPPED.getProgressIndex()).isEqualTo(2);
        assertThat(OrderStatus.DELIVERED.getProgressIndex()).isEqualTo(3);
        assertThat(OrderStatus.CANCELLED.getProgressIndex()).isEqualTo(-1);
    }

    // ── 유틸리티 메서드 ──

    @Test
    @DisplayName("codes — 모든 상태 코드 문자열 리스트")
    void codes_returnsAllNames() {
        assertThat(OrderStatus.codes())
                .containsExactly("PENDING", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");
    }

    @Test
    @DisplayName("labelsByCode — 상태코드 → 한글 라벨 맵")
    void labelsByCode_returnsLabels() {
        var labels = OrderStatus.labelsByCode();
        assertThat(labels).hasSize(5);
        assertThat(labels.get("PENDING")).isEqualTo("결제대기");
        assertThat(labels.get("CANCELLED")).isEqualTo("주문취소");
    }

    @Test
    @DisplayName("badgeClassesByCode — 상태코드 → CSS 클래스 맵")
    void badgeClassesByCode_returnsClasses() {
        var badges = OrderStatus.badgeClassesByCode();
        assertThat(badges).hasSize(5);
        assertThat(badges.get("SHIPPED")).contains("bg-blue");
    }
}

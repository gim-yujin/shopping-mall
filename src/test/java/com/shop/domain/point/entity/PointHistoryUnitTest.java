package com.shop.domain.point.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PointHistory 엔티티 단위 테스트.
 *
 * <p>PointHistory는 포인트 변동 이력을 기록하는 불변 엔티티이다.
 * 생성 후 변경 메서드가 없으므로, 생성자 초기화와 getter 정확성을 검증한다.
 * 기존 테스트에서 직접 인스턴스를 생성하지 않아 LINE 75% / METHOD 55%였다.</p>
 */
class PointHistoryUnitTest {

    @Test
    @DisplayName("생성자가 모든 필드를 올바르게 초기화한다")
    void constructor_initializesAllFields() {
        // given & when: EARN 타입의 포인트 이력 생성
        PointHistory history = new PointHistory(
                1L,             // userId
                PointHistory.EARN,  // changeType
                500,            // amount (적립 포인트)
                1500,           // balanceAfter (적립 후 잔액)
                "ORDER",        // referenceType
                100L,           // referenceId (주문 ID)
                "주문 적립 포인트"  // description
        );

        // then: 모든 필드가 정확히 설정
        assertThat(history.getUserId()).isEqualTo(1L);
        assertThat(history.getChangeType()).isEqualTo("EARN");
        assertThat(history.getAmount()).isEqualTo(500);
        assertThat(history.getBalanceAfter()).isEqualTo(1500);
        assertThat(history.getReferenceType()).isEqualTo("ORDER");
        assertThat(history.getReferenceId()).isEqualTo(100L);
        assertThat(history.getDescription()).isEqualTo("주문 적립 포인트");
        assertThat(history.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("historyId는 JPA가 할당하므로 초기값은 null이다")
    void historyId_isNullBeforePersist() {
        // given & when
        PointHistory history = new PointHistory(
                2L, PointHistory.USE, 300, 700, "ORDER", 200L, "주문 사용");

        // then
        assertThat(history.getHistoryId()).isNull();
    }

    @Test
    @DisplayName("changeType 상수가 올바른 값을 가진다")
    void changeTypeConstants_haveCorrectValues() {
        // PointHistory의 5가지 변동 타입 상수 검증
        assertThat(PointHistory.EARN).isEqualTo("EARN");
        assertThat(PointHistory.USE).isEqualTo("USE");
        assertThat(PointHistory.REFUND).isEqualTo("REFUND");
        assertThat(PointHistory.EXPIRE).isEqualTo("EXPIRE");
        assertThat(PointHistory.ADJUST).isEqualTo("ADJUST");
    }
}

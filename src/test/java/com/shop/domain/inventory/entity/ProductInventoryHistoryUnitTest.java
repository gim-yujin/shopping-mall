package com.shop.domain.inventory.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductInventoryHistory 엔티티 단위 테스트.
 *
 * <p>재고 변동 이력을 기록하는 불변 엔티티이다.
 * 생성 후 변경 불가하므로 생성자 초기화와 getter 정확성을 검증한다.
 * 기존 테스트에서 직접 인스턴스를 생성하지 않아 LINE 77% / METHOD 58%였다.</p>
 */
class ProductInventoryHistoryUnitTest {

    @Test
    @DisplayName("생성자가 모든 필드를 올바르게 초기화한다")
    void constructor_initializesAllFields() {
        // given & when: 주문에 의한 재고 차감 이력 생성
        ProductInventoryHistory history = new ProductInventoryHistory(
                1L,          // productId
                "DECREASE",  // changeType (차감)
                -5,          // changeAmount (음수: 차감)
                100,         // beforeQuantity
                95,          // afterQuantity
                "주문 출고",  // reason
                200L,        // referenceId (주문 ID)
                3L           // createdBy (관리자 ID)
        );

        // then: 모든 필드가 정확히 설정
        assertThat(history.getProductId()).isEqualTo(1L);
        assertThat(history.getChangeType()).isEqualTo("DECREASE");
        assertThat(history.getChangeAmount()).isEqualTo(-5);
        assertThat(history.getBeforeQuantity()).isEqualTo(100);
        assertThat(history.getAfterQuantity()).isEqualTo(95);
        assertThat(history.getReason()).isEqualTo("주문 출고");
        assertThat(history.getReferenceId()).isEqualTo(200L);
        assertThat(history.getCreatedBy()).isEqualTo(3L);
        assertThat(history.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("historyId는 JPA가 할당하므로 초기값은 null이다")
    void historyId_isNullBeforePersist() {
        // given & when
        ProductInventoryHistory history = new ProductInventoryHistory(
                1L, "INCREASE", 10, 90, 100, "입고", null, null);

        // then
        assertThat(history.getHistoryId()).isNull();
    }
}

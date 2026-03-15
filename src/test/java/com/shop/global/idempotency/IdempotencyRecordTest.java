package com.shop.global.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdempotencyRecord 엔티티 단위 테스트.
 *
 * <p>상태 전이(PROCESSING → COMPLETED / FAILED)와 상태 조회 메서드를 검증한다.</p>
 */
class IdempotencyRecordTest {

    @Test
    @DisplayName("생성 시 PROCESSING 상태이다")
    void initialStatusIsProcessing() {
        IdempotencyRecord record = new IdempotencyRecord(1L, "test-key", "ORDER");

        assertThat(record.isProcessing()).isTrue();
        assertThat(record.isCompleted()).isFalse();
        assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.STATUS_PROCESSING);
        assertThat(record.getResourceId()).isNull();
        assertThat(record.getResponseBody()).isNull();
    }

    @Test
    @DisplayName("markCompleted: COMPLETED 상태로 전이하고 응답 데이터를 저장한다")
    void markCompletedStoresResponseData() {
        IdempotencyRecord record = new IdempotencyRecord(1L, "test-key", "ORDER");

        record.markCompleted(100L, "{\"success\":true}", 201);

        assertThat(record.isCompleted()).isTrue();
        assertThat(record.isProcessing()).isFalse();
        assertThat(record.getResourceId()).isEqualTo(100L);
        assertThat(record.getResponseBody()).isEqualTo("{\"success\":true}");
        assertThat(record.getHttpStatus()).isEqualTo(201);
        assertThat(record.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("markCompletedForSsr: COMPLETED 상태로 전이하되 응답 JSON은 null이다")
    void markCompletedForSsrStoresOnlyResourceId() {
        // SSR에서는 리다이렉트 URL에 orderId를 사용하므로 responseBody가 불필요
        IdempotencyRecord record = new IdempotencyRecord(1L, "test-key", "ORDER");

        record.markCompletedForSsr(100L);

        assertThat(record.isCompleted()).isTrue();
        assertThat(record.getResourceId()).isEqualTo(100L);
        assertThat(record.getResponseBody()).isNull();
        assertThat(record.getHttpStatus()).isNull();
    }

    @Test
    @DisplayName("markFailed: FAILED 상태로 전이한다")
    void markFailedTransitionsToFailed() {
        IdempotencyRecord record = new IdempotencyRecord(1L, "test-key", "ORDER");

        record.markFailed();

        assertThat(record.isProcessing()).isFalse();
        assertThat(record.isCompleted()).isFalse();
        assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.STATUS_FAILED);
        assertThat(record.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("생성자가 필드를 올바르게 초기화한다")
    void constructorSetsFields() {
        IdempotencyRecord record = new IdempotencyRecord(42L, "my-uuid-key", "ORDER");

        assertThat(record.getUserId()).isEqualTo(42L);
        assertThat(record.getIdempotencyKey()).isEqualTo("my-uuid-key");
        assertThat(record.getResourceType()).isEqualTo("ORDER");
        assertThat(record.getCreatedAt()).isNotNull();
    }
}

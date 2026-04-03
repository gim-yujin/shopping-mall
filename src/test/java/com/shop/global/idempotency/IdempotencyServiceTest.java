package com.shop.global.idempotency;

import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IdempotencyService 단위 테스트.
 *
 * <p>멱등성 키 검증, 레코드 조회/생성/상태 전환, FAILED 재시도 등
 * 핵심 비즈니스 로직을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyRecordRepository repository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Nested
    @DisplayName("validateKey — 멱등성 키 형식 검증")
    class ValidateKeyTest {

        @Test
        @DisplayName("유효한 UUID 형식의 키는 검증을 통과한다")
        void validUuidKey() {
            // UUID 형식 키는 영문+숫자+하이픈으로 구성되어 유효하다
            idempotencyService.validateKey("550e8400-e29b-41d4-a716-446655440000");
        }

        @Test
        @DisplayName("null 키는 BusinessException을 발생시킨다")
        void nullKey() {
            assertThatThrownBy(() -> idempotencyService.validateKey(null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");
        }

        @Test
        @DisplayName("빈 문자열 키는 BusinessException을 발생시킨다")
        void blankKey() {
            assertThatThrownBy(() -> idempotencyService.validateKey("  "))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");
        }

        @Test
        @DisplayName("64자를 초과하는 키는 BusinessException을 발생시킨다")
        void tooLongKey() {
            // MAX_KEY_LENGTH(64)를 초과하는 문자열 — 과도한 키 길이로 DB 부하 방지
            String longKey = "a".repeat(IdempotencyService.MAX_KEY_LENGTH + 1);
            assertThatThrownBy(() -> idempotencyService.validateKey(longKey))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");
        }

        @Test
        @DisplayName("특수문자가 포함된 키는 BusinessException을 발생시킨다")
        void specialCharKey() {
            // SQL injection 방어를 위해 영문+숫자+하이픈만 허용
            assertThatThrownBy(() -> idempotencyService.validateKey("key'; DROP TABLE--"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_IDEMPOTENCY_KEY");
        }
    }

    @Nested
    @DisplayName("findExisting — 기존 레코드 조회")
    class FindExistingTest {

        @Test
        @DisplayName("레코드가 존재하면 Optional에 담아 반환한다")
        void returnsExistingRecord() {
            IdempotencyRecord record = new IdempotencyRecord(1L, "test-key", "ORDER");
            when(repository.findByUserIdAndIdempotencyKey(1L, "test-key"))
                    .thenReturn(Optional.of(record));

            Optional<IdempotencyRecord> result = idempotencyService.findExisting(1L, "test-key");

            assertThat(result).isPresent();
            assertThat(result.get().getIdempotencyKey()).isEqualTo("test-key");
        }

        @Test
        @DisplayName("레코드가 없으면 빈 Optional을 반환한다")
        void returnsEmptyForNewKey() {
            when(repository.findByUserIdAndIdempotencyKey(1L, "new-key"))
                    .thenReturn(Optional.empty());

            Optional<IdempotencyRecord> result = idempotencyService.findExisting(1L, "new-key");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("initRecord — PROCESSING 레코드 생성")
    class InitRecordTest {

        @Test
        @DisplayName("새 PROCESSING 레코드를 생성하고 반환한다")
        void createsProcessingRecord() {
            when(repository.save(any(IdempotencyRecord.class))).thenAnswer(inv -> {
                IdempotencyRecord r = inv.getArgument(0);
                ReflectionTestUtils.setField(r, "recordId", 100L);
                return r;
            });

            IdempotencyRecord result = idempotencyService.initRecord(1L, "new-key", "ORDER");

            // PROCESSING 상태로 생성되었는지 검증
            assertThat(result.getStatus()).isEqualTo(IdempotencyRecord.STATUS_PROCESSING);
            assertThat(result.getUserId()).isEqualTo(1L);
            assertThat(result.getIdempotencyKey()).isEqualTo("new-key");
            assertThat(result.getResourceType()).isEqualTo("ORDER");
            verify(repository).save(any(IdempotencyRecord.class));
        }
    }

    @Nested
    @DisplayName("markCompleted — 완료 상태 전환")
    class MarkCompletedTest {

        @Test
        @DisplayName("API용: COMPLETED 상태로 전환하고 응답 JSON을 저장한다")
        void marksCompletedWithResponse() {
            IdempotencyRecord record = new IdempotencyRecord(1L, "key", "ORDER");
            ReflectionTestUtils.setField(record, "recordId", 10L);
            when(repository.findById(10L)).thenReturn(Optional.of(record));

            idempotencyService.markCompleted(10L, 999L, "{\"success\":true}", 201);

            assertThat(record.isCompleted()).isTrue();
            assertThat(record.getResourceId()).isEqualTo(999L);
            assertThat(record.getResponseBody()).isEqualTo("{\"success\":true}");
            assertThat(record.getHttpStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("SSR용: COMPLETED 상태로 전환하고 리소스 ID만 저장한다")
        void marksCompletedForSsr() {
            IdempotencyRecord record = new IdempotencyRecord(1L, "key", "ORDER");
            ReflectionTestUtils.setField(record, "recordId", 10L);
            when(repository.findById(10L)).thenReturn(Optional.of(record));

            idempotencyService.markCompletedForSsr(10L, 999L);

            // SSR에서는 responseBody가 null이고 resourceId만 저장된다
            assertThat(record.isCompleted()).isTrue();
            assertThat(record.getResourceId()).isEqualTo(999L);
            assertThat(record.getResponseBody()).isNull();
        }
    }

    @Nested
    @DisplayName("markFailed — 실패 상태 전환")
    class MarkFailedTest {

        @Test
        @DisplayName("FAILED 상태로 전환한다")
        void marksFailed() {
            IdempotencyRecord record = new IdempotencyRecord(1L, "key", "ORDER");
            ReflectionTestUtils.setField(record, "recordId", 10L);
            when(repository.findById(10L)).thenReturn(Optional.of(record));

            idempotencyService.markFailed(10L);

            assertThat(record.getStatus()).isEqualTo(IdempotencyRecord.STATUS_FAILED);
        }

        @Test
        @DisplayName("레코드가 없어도 예외 없이 무시한다")
        void ignoresMissingRecord() {
            // 주문 트랜잭션 롤백 후 FAILED 전환 시 레코드가 이미 삭제되었을 수 있음
            when(repository.findById(999L)).thenReturn(Optional.empty());

            idempotencyService.markFailed(999L);  // 예외 없이 정상 종료
        }
    }

    @Nested
    @DisplayName("executeWithCompletion / executeAndMarkCompleted — 원자적 완료 래퍼")
    class ExecuteWithCompletionTest {

        @Test
        @DisplayName("executeWithCompletion은 action 결과를 반환하고 COMPLETED로 전환한다")
        void executeWithCompletion_marksCompletedAndReturnsResult() {
            IdempotencyRecord record = new IdempotencyRecord(1L, "key", "ORDER");
            ReflectionTestUtils.setField(record, "recordId", 10L);
            when(repository.findById(10L)).thenReturn(Optional.of(record));

            Long result = idempotencyService.executeWithCompletion(10L, () -> 999L, id -> id, 201);

            assertThat(result).isEqualTo(999L);
            assertThat(record.isCompleted()).isTrue();
            assertThat(record.getResourceId()).isEqualTo(999L);
            assertThat(record.getHttpStatus()).isEqualTo(201);
            assertThat(record.getResponseBody()).isNull();
        }

        @Test
        @DisplayName("executeAndMarkCompleted는 void action 후 COMPLETED로 전환한다")
        void executeAndMarkCompleted_marksCompleted() {
            IdempotencyRecord record = new IdempotencyRecord(1L, "key", "ORDER_CANCEL");
            ReflectionTestUtils.setField(record, "recordId", 20L);
            when(repository.findById(20L)).thenReturn(Optional.of(record));

            idempotencyService.executeAndMarkCompleted(20L, 555L, 200, () -> {
                // no-op
            });

            assertThat(record.isCompleted()).isTrue();
            assertThat(record.getResourceId()).isEqualTo(555L);
            assertThat(record.getHttpStatus()).isEqualTo(200);
            assertThat(record.getResponseBody()).isNull();
        }
    }

    @Nested
    @DisplayName("retryAfterFailure — FAILED 재시도")
    class RetryAfterFailureTest {

        @Test
        @DisplayName("FAILED 레코드를 삭제하고 새 PROCESSING 레코드를 생성한다")
        void deletesFailedAndCreatesNew() {
            when(repository.deleteFailedRecord(1L, "retry-key")).thenReturn(1);
            when(repository.save(any(IdempotencyRecord.class))).thenAnswer(inv -> {
                IdempotencyRecord r = inv.getArgument(0);
                ReflectionTestUtils.setField(r, "recordId", 200L);
                return r;
            });

            IdempotencyRecord result = idempotencyService.retryAfterFailure(1L, "retry-key", "ORDER");

            assertThat(result.getStatus()).isEqualTo(IdempotencyRecord.STATUS_PROCESSING);
            verify(repository).deleteFailedRecord(1L, "retry-key");
            verify(repository).save(any(IdempotencyRecord.class));
        }

        @Test
        @DisplayName("FAILED 레코드가 이미 삭제되었으면 BusinessException을 발생시킨다")
        void throwsWhenFailedAlreadyDeleted() {
            // 다른 스레드가 이미 FAILED를 삭제하고 재처리 중인 경우
            when(repository.deleteFailedRecord(1L, "retry-key")).thenReturn(0);

            assertThatThrownBy(() -> idempotencyService.retryAfterFailure(1L, "retry-key", "ORDER"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_CONFLICT");
        }
    }
}

package com.shop.global.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * [Phase 15] OutboxDeadLetterService 단위 테스트.
 *
 * <p>Dead Letter 이벤트의 재시도 큐 등록(requeue), 일괄 재시도, 영구 폐기(discard)
 * 기능을 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxDeadLetterServiceTest {

    @Mock
    private OutboxEventRepository repository;

    @InjectMocks
    private OutboxDeadLetterService deadLetterService;

    private OutboxEvent createDeadLetterEvent(Long eventId) {
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED,
                "{\"orderId\":1,\"userId\":2,\"finalAmount\":50000}");
        ReflectionTestUtils.setField(event, "eventId", eventId);
        event.moveToDeadLetter("테스트 실패 원인");
        return event;
    }

    @Nested
    @DisplayName("개별 재시도 — requeueById")
    class RequeueById {

        @Test
        @DisplayName("Dead Letter 이벤트를 PENDING으로 되돌리고 retryCount를 0으로 초기화한다")
        void requeueResetsToPending() {
            OutboxEvent event = createDeadLetterEvent(1L);
            when(repository.findById(1L)).thenReturn(Optional.of(event));

            boolean result = deadLetterService.requeueById(1L);

            assertThat(result).isTrue();
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(event.getRetryCount()).isZero();
            assertThat(event.getLastError()).isNull();
            assertThat(event.getProcessedAt()).isNull();
            assertThat(event.getNextRetryAt()).isNull();
        }

        @Test
        @DisplayName("존재하지 않는 이벤트 ID에 대해 false를 반환한다")
        void returnsFalseForNonExistent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            assertThat(deadLetterService.requeueById(999L)).isFalse();
        }

        @Test
        @DisplayName("DEAD_LETTER가 아닌 이벤트에 대해 false를 반환한다")
        void returnsFalseForNonDeadLetter() {
            OutboxEvent pendingEvent = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED, "{}");
            ReflectionTestUtils.setField(pendingEvent, "eventId", 2L);
            when(repository.findById(2L)).thenReturn(Optional.of(pendingEvent));

            assertThat(deadLetterService.requeueById(2L)).isFalse();
        }
    }

    @Nested
    @DisplayName("일괄 재시도 — requeueAll")
    class RequeueAll {

        @Test
        @DisplayName("모든 Dead Letter 이벤트를 PENDING으로 되돌린다")
        void requeueAllDeadLetters() {
            OutboxEvent event1 = createDeadLetterEvent(1L);
            OutboxEvent event2 = createDeadLetterEvent(2L);
            when(repository.findDeadLetterEvents(Integer.MAX_VALUE))
                    .thenReturn(List.of(event1, event2));

            int count = deadLetterService.requeueAll();

            assertThat(count).isEqualTo(2);
            assertThat(event1.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(event2.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        }

        @Test
        @DisplayName("Dead Letter가 없으면 0을 반환한다")
        void returnsZeroWhenEmpty() {
            when(repository.findDeadLetterEvents(Integer.MAX_VALUE))
                    .thenReturn(List.of());

            assertThat(deadLetterService.requeueAll()).isZero();
        }
    }

    @Nested
    @DisplayName("영구 폐기 — discardById")
    class DiscardById {

        @Test
        @DisplayName("Dead Letter 이벤트를 삭제하고 true를 반환한다")
        void deletesDeadLetterEvent() {
            OutboxEvent event = createDeadLetterEvent(1L);
            when(repository.findById(1L)).thenReturn(Optional.of(event));

            boolean result = deadLetterService.discardById(1L);

            assertThat(result).isTrue();
            verify(repository).delete(event);
        }

        @Test
        @DisplayName("DEAD_LETTER가 아닌 이벤트는 삭제하지 않고 false를 반환한다")
        void doesNotDeleteNonDeadLetter() {
            OutboxEvent pendingEvent = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED, "{}");
            ReflectionTestUtils.setField(pendingEvent, "eventId", 2L);
            when(repository.findById(2L)).thenReturn(Optional.of(pendingEvent));

            assertThat(deadLetterService.discardById(2L)).isFalse();
            verify(repository, never()).delete(any());
        }
    }
}

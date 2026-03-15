package com.shop.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * OutboxEventPoller 단위 테스트.
 *
 * <p>PENDING 이벤트 폴링, 캐시 무효화 실행, 재시도, 영구 실패 전이를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventPollerTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ProductCacheEvictHelper cacheEvictHelper = mock(ProductCacheEvictHelper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxEventPoller createPoller(int maxRetries) {
        return new OutboxEventPoller(repository, cacheEvictHelper, objectMapper, maxRetries, 100);
    }

    private OutboxEvent createEvent(Long eventId, String payload) {
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, payload);
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    @Nested
    @DisplayName("정상 처리")
    class NormalProcessing {

        @Test
        @DisplayName("PENDING 이벤트를 처리하고 PROCESSED로 전이한다")
        void processesAndMarksCompleted() {
            OutboxEvent event = createEvent(1L, "{\"productIds\":[10,20]}");
            when(repository.findPendingEvents(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            // 캐시 무효화가 올바른 상품 ID로 호출되었는지 검증
            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L, 20L));
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(event.getProcessedAt()).isNotNull();
        }

        @Test
        @DisplayName("PENDING 이벤트가 없으면 아무 작업도 하지 않는다")
        void noOpWhenNoPendingEvents() {
            when(repository.findPendingEvents(anyInt())).thenReturn(Collections.emptyList());

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verifyNoInteractions(cacheEvictHelper);
        }

        @Test
        @DisplayName("여러 이벤트를 순서대로 처리한다")
        void processesMultipleEvents() {
            OutboxEvent event1 = createEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent event2 = createEvent(2L, "{\"productIds\":[20,30]}");
            when(repository.findPendingEvents(anyInt())).thenReturn(List.of(event1, event2));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L));
            verify(cacheEvictHelper).evictProductDetailCaches(List.of(20L, 30L));
            assertThat(event1.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(event2.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }
    }

    @Nested
    @DisplayName("재시도 및 영구 실패")
    class RetryAndFailure {

        @Test
        @DisplayName("처리 실패 시 retry_count를 증가시키고 PENDING을 유지한다")
        void incrementsRetryOnFailure() {
            OutboxEvent event = createEvent(1L, "{\"productIds\":[10]}");
            // 캐시 무효화에서 예외 발생 시뮬레이션
            doThrow(new RuntimeException("캐시 서버 다운"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findPendingEvents(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            // retry_count 증가, 상태는 PENDING 유지 (다음 폴링에서 재시도)
            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        }

        @Test
        @DisplayName("MAX_RETRIES 초과 시 FAILED로 전이한다")
        void marksFailedAfterMaxRetries() {
            OutboxEvent event = createEvent(1L, "{\"productIds\":[10]}");
            // 이미 4번 실패한 상태 (maxRetries=5이면 다음 실패가 5번째)
            ReflectionTestUtils.setField(event, "retryCount", 4);
            doThrow(new RuntimeException("영구 실패"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findPendingEvents(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            // 5번째 실패 → FAILED로 영구 전이
            assertThat(event.getRetryCount()).isEqualTo(5);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        }

        @Test
        @DisplayName("하나의 이벤트 실패가 다른 이벤트 처리를 중단하지 않는다")
        void failureDoesNotBlockOtherEvents() {
            OutboxEvent failEvent = createEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent okEvent = createEvent(2L, "{\"productIds\":[20]}");

            // 첫 번째 이벤트만 실패
            doThrow(new RuntimeException("실패"))
                    .doNothing()
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findPendingEvents(anyInt())).thenReturn(List.of(failEvent, okEvent));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            // 첫 번째: 실패로 retry_count 증가, PENDING 유지
            assertThat(failEvent.getRetryCount()).isEqualTo(1);
            assertThat(failEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            // 두 번째: 정상 처리
            assertThat(okEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }
    }
}

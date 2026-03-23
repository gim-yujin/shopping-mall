package com.shop.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.order.service.OrderNotificationService;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import com.shop.global.metrics.OutboxMetrics;
import com.shop.global.outbox.handler.OrderCancelledEventHandler;
import com.shop.global.outbox.handler.OrderCreatedEventHandler;
import com.shop.global.outbox.handler.StockChangedEventHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.junit.jupiter.api.BeforeEach;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * OutboxEventPoller 단위 테스트.
 *
 * <p>[Phase 6] Strategy 패턴 적용 후 핸들러 기반 테스트로 변경.
 * 실제 핸들러(StockChangedEventHandler 등)를 생성하여
 * 폴러의 라우팅과 상태 전이를 검증한다.</p>
 *
 * <p>[Phase 15] 지수 백오프 재시도 및 Dead Letter 전이 테스트 추가.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventPollerTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ProductCacheEvictHelper cacheEvictHelper = mock(ProductCacheEvictHelper.class);
    private final OrderNotificationService notificationService = mock(OrderNotificationService.class);
    private final OutboxMetrics outboxMetrics = mock(OutboxMetrics.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 기본 stub: 두 분리 쿼리가 모두 빈 리스트를 반환하도록 설정.
        // 개별 테스트에서 필요한 쿼리만 override한다.
        lenient().when(repository.findFirstAttemptEventsForUpdate(anyInt()))
                .thenReturn(Collections.emptyList());
        lenient().when(repository.findRetryEventsForUpdate(anyInt()))
                .thenReturn(Collections.emptyList());
    }

    /**
     * 실제 핸들러들을 생성하여 폴러에 주입한다.
     * [Phase 15] OutboxMetrics 및 retryBaseDelaySec 파라미터 추가.
     * Retry Budget: retryBudgetPerPoll=20, jitterFactor=0(테스트 결정성 보장).
     */
    private OutboxEventPoller createPoller(int maxRetries) {
        return createPoller(maxRetries, 100, 20, 0.0);
    }

    private OutboxEventPoller createPoller(int maxRetries, int batchSize,
                                            int retryBudgetPerPoll, double jitterFactor) {
        List<OutboxEventHandler> handlers = List.of(
                new StockChangedEventHandler(cacheEvictHelper, objectMapper),
                new OrderCreatedEventHandler(notificationService, objectMapper),
                new OrderCancelledEventHandler(notificationService, objectMapper));
        return new OutboxEventPoller(repository, handlers, outboxMetrics,
                maxRetries, batchSize, 10, retryBudgetPerPoll, jitterFactor);
    }

    private OutboxEvent createStockEvent(Long eventId, String payload) {
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, payload);
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    private OutboxEvent createOrderCreatedEvent(Long eventId) {
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CREATED,
                "{\"orderId\":1,\"userId\":2,\"finalAmount\":50000}");
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    private OutboxEvent createOrderCancelledEvent(Long eventId) {
        OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_ORDER_CANCELLED,
                "{\"orderId\":1,\"userId\":2,\"refundedAmount\":50000}");
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    @Nested
    @DisplayName("정상 처리")
    class NormalProcessing {

        @Test
        @DisplayName("PENDING 이벤트를 처리하고 PROCESSED로 전이한다")
        void processesAndMarksCompleted() {
            OutboxEvent event = createStockEvent(1L, "{\"productIds\":[10,20]}");
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L, 20L));
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(event.getProcessedAt()).isNotNull();
            verify(outboxMetrics).recordProcessed();
        }

        @Test
        @DisplayName("PENDING 이벤트가 없으면 아무 작업도 하지 않는다")
        void noOpWhenNoPendingEvents() {
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(Collections.emptyList());

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verifyNoInteractions(cacheEvictHelper);
            verifyNoInteractions(outboxMetrics);
        }

        @Test
        @DisplayName("여러 이벤트를 순서대로 처리한다")
        void processesMultipleEvents() {
            OutboxEvent event1 = createStockEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent event2 = createStockEvent(2L, "{\"productIds\":[20,30]}");
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event1, event2));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L));
            verify(cacheEvictHelper).evictProductDetailCaches(List.of(20L, 30L));
            assertThat(event1.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(event2.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            verify(outboxMetrics, times(2)).recordProcessed();
        }

        /**
         * [Phase 6] ORDER_CREATED 이벤트가 OrderNotificationService를 호출하는지 검증.
         */
        @Test
        @DisplayName("ORDER_CREATED 이벤트를 처리하여 주문 확인 알림을 발송한다")
        void processesOrderCreatedEvent() {
            OutboxEvent event = createOrderCreatedEvent(1L);
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(notificationService).sendOrderConfirmation(1L, 2L, new java.math.BigDecimal("50000"));
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }

        /**
         * [Phase 6] ORDER_CANCELLED 이벤트가 OrderNotificationService를 호출하는지 검증.
         */
        @Test
        @DisplayName("ORDER_CANCELLED 이벤트를 처리하여 취소 알림을 발송한다")
        void processesOrderCancelledEvent() {
            OutboxEvent event = createOrderCancelledEvent(1L);
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(notificationService).sendCancellationNotice(1L, 2L, new java.math.BigDecimal("50000"));
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }

        /**
         * [Phase 6] 여러 유형의 이벤트가 혼합되어도 올바른 핸들러로 라우팅된다.
         */
        @Test
        @DisplayName("혼합된 이벤트 유형이 올바른 핸들러로 라우팅된다")
        void routesMixedEventTypes() {
            OutboxEvent stockEvent = createStockEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent orderEvent = createOrderCreatedEvent(2L);
            when(repository.findFirstAttemptEventsForUpdate(anyInt()))
                    .thenReturn(List.of(stockEvent, orderEvent));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L));
            verify(notificationService).sendOrderConfirmation(1L, 2L, new java.math.BigDecimal("50000"));
            assertThat(stockEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(orderEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }
    }

    @Nested
    @DisplayName("[Phase 15] 지수 백오프 재시도 및 Dead Letter")
    class RetryAndDeadLetter {

        /**
         * [Phase 15] 처리 실패 시 지수 백오프로 재시도를 예약하고 PENDING을 유지한다.
         * nextRetryAt이 설정되어 다음 폴링에서 즉시 재시도하지 않는다.
         */
        @Test
        @DisplayName("처리 실패 시 지수 백오프로 재시도를 예약하고 PENDING을 유지한다")
        void schedulesRetryWithExponentialBackoff() {
            OutboxEvent event = createStockEvent(1L, "{\"productIds\":[10]}");
            doThrow(new RuntimeException("캐시 서버 다운"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(event.getNextRetryAt()).isNotNull();
            assertThat(event.getLastError()).isEqualTo("캐시 서버 다운");
            verify(outboxMetrics).recordRetry();
        }

        /**
         * [Phase 15] MAX_RETRIES 초과 시 DEAD_LETTER로 전이하고 lastError를 기록한다.
         */
        @Test
        @DisplayName("MAX_RETRIES 초과 시 DEAD_LETTER로 전이한다")
        void movesToDeadLetterAfterMaxRetries() {
            OutboxEvent event = createStockEvent(1L, "{\"productIds\":[10]}");
            ReflectionTestUtils.setField(event, "retryCount", 4);
            doThrow(new RuntimeException("영구 실패"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(event.getRetryCount()).isEqualTo(5);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_DEAD_LETTER);
            assertThat(event.getProcessedAt()).isNotNull();
            assertThat(event.getLastError()).isEqualTo("영구 실패");
            verify(outboxMetrics).recordDeadLetter();
        }

        @Test
        @DisplayName("하나의 이벤트 실패가 다른 이벤트 처리를 중단하지 않는다")
        void failureDoesNotBlockOtherEvents() {
            OutboxEvent failEvent = createStockEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent okEvent = createStockEvent(2L, "{\"productIds\":[20]}");

            doThrow(new RuntimeException("실패"))
                    .doNothing()
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(failEvent, okEvent));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(failEvent.getRetryCount()).isEqualTo(1);
            assertThat(failEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(okEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }

        /**
         * [Phase 15] 2회 연속 실패 시 지수 백오프 간격이 증가하는지 검증한다.
         */
        @Test
        @DisplayName("연속 실패 시 지수 백오프 간격이 점진적으로 증가한다")
        void backoffIntervalIncreasesExponentially() {
            OutboxEvent event = new OutboxEvent(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED, "{}");
            ReflectionTestUtils.setField(event, "eventId", 1L);

            // 1차 실패: baseDelay=10 × 2^0 = 10초
            event.scheduleRetry("error1", 10);
            java.time.LocalDateTime firstRetryAt = event.getNextRetryAt();

            // 2차 실패: baseDelay=10 × 2^1 = 20초
            event.scheduleRetry("error2", 10);
            java.time.LocalDateTime secondRetryAt = event.getNextRetryAt();

            // 2차 재시도 시각이 1차보다 더 뒤여야 한다 (지수 증가 확인)
            assertThat(secondRetryAt).isAfter(firstRetryAt);
            assertThat(event.getRetryCount()).isEqualTo(2);
            assertThat(event.getLastError()).isEqualTo("error2");
        }
    }

    @Nested
    @DisplayName("알 수 없는 이벤트 유형")
    class UnknownEventType {

        @Test
        @DisplayName("알 수 없는 이벤트 유형은 경고 로그 후 PROCESSED로 전이한다")
        void unknownEventTypeIsProcessed() {
            OutboxEvent event = new OutboxEvent("UNKNOWN_TYPE", "{}");
            ReflectionTestUtils.setField(event, "eventId", 99L);
            when(repository.findFirstAttemptEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            // 알 수 없는 유형이지만 예외가 발생하지 않으므로 PROCESSED로 전이
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }
    }

    @Nested
    @DisplayName("Retry Budget — 신규/재시도 이벤트 분리 처리")
    class RetryBudget {

        @Test
        @DisplayName("신규 이벤트와 재시도 이벤트가 각각의 쿼리로 조회되어 모두 처리된다")
        void processesFirstAttemptAndRetryEventsSeparately() {
            OutboxEvent firstAttempt = createStockEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent retryEvent = createStockEvent(2L, "{\"productIds\":[20]}");
            // 재시도 이벤트: retryCount > 0이고 nextRetryAt이 설정된 상태
            ReflectionTestUtils.setField(retryEvent, "retryCount", 1);

            when(repository.findFirstAttemptEventsForUpdate(anyInt()))
                    .thenReturn(List.of(firstAttempt));
            when(repository.findRetryEventsForUpdate(anyInt()))
                    .thenReturn(List.of(retryEvent));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(firstAttempt.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(retryEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            verify(outboxMetrics, times(2)).recordProcessed();
        }

        @Test
        @DisplayName("재시도 쿼리에 retryBudgetPerPoll 값이 전달된다")
        void retryBudgetLimitsRetryQuery() {
            OutboxEventPoller poller = createPoller(5, 100, 3, 0.0);
            poller.pollAndProcess();

            // batchSize=100으로 신규 이벤트 조회
            verify(repository).findFirstAttemptEventsForUpdate(100);
            // retryBudgetPerPoll=3으로 재시도 이벤트 조회
            verify(repository).findRetryEventsForUpdate(3);
        }

        @Test
        @DisplayName("신규 이벤트만 있고 재시도 이벤트 없음 → 신규만 처리")
        void onlyFirstAttemptEvents_processedNormally() {
            OutboxEvent event = createStockEvent(1L, "{\"productIds\":[10]}");
            when(repository.findFirstAttemptEventsForUpdate(anyInt()))
                    .thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            verify(outboxMetrics).recordProcessed();
        }

        @Test
        @DisplayName("재시도 이벤트만 있고 신규 이벤트 없음 → 재시도만 처리")
        void onlyRetryEvents_processedNormally() {
            OutboxEvent retryEvent = createStockEvent(1L, "{\"productIds\":[10]}");
            ReflectionTestUtils.setField(retryEvent, "retryCount", 2);
            when(repository.findRetryEventsForUpdate(anyInt()))
                    .thenReturn(List.of(retryEvent));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(retryEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            verify(outboxMetrics).recordProcessed();
        }

        @Test
        @DisplayName("재시도 이벤트 실패 시 jitterFactor가 scheduleRetry에 전달된다")
        void retryEventFailure_usesJitterFactor() {
            OutboxEvent retryEvent = createStockEvent(1L, "{\"productIds\":[10]}");
            ReflectionTestUtils.setField(retryEvent, "retryCount", 1);
            doThrow(new RuntimeException("장애"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findRetryEventsForUpdate(anyInt()))
                    .thenReturn(List.of(retryEvent));

            // jitterFactor=0.0이므로 고정 백오프와 동일하게 동작
            OutboxEventPoller poller = createPoller(5, 100, 20, 0.0);
            poller.pollAndProcess();

            assertThat(retryEvent.getRetryCount()).isEqualTo(2);
            assertThat(retryEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(retryEvent.getNextRetryAt()).isNotNull();
            verify(outboxMetrics).recordRetry();
        }
    }
}

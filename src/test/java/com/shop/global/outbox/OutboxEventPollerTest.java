package com.shop.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.order.service.OrderNotificationService;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import com.shop.global.outbox.handler.OrderCancelledEventHandler;
import com.shop.global.outbox.handler.OrderCreatedEventHandler;
import com.shop.global.outbox.handler.StockChangedEventHandler;
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
 * <p>[Phase 6] Strategy 패턴 적용 후 핸들러 기반 테스트로 변경.
 * 실제 핸들러(StockChangedEventHandler 등)를 생성하여
 * 폴러의 라우팅과 상태 전이를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventPollerTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final ProductCacheEvictHelper cacheEvictHelper = mock(ProductCacheEvictHelper.class);
    private final OrderNotificationService notificationService = mock(OrderNotificationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 실제 핸들러들을 생성하여 폴러에 주입한다.
     * Strategy 패턴의 eventType → Handler 매핑이 올바르게 구성되는지 검증한다.
     */
    private OutboxEventPoller createPoller(int maxRetries) {
        List<OutboxEventHandler> handlers = List.of(
                new StockChangedEventHandler(cacheEvictHelper, objectMapper),
                new OrderCreatedEventHandler(notificationService, objectMapper),
                new OrderCancelledEventHandler(notificationService, objectMapper));
        return new OutboxEventPoller(repository, handlers, maxRetries, 100);
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
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L, 20L));
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(event.getProcessedAt()).isNotNull();
        }

        @Test
        @DisplayName("PENDING 이벤트가 없으면 아무 작업도 하지 않는다")
        void noOpWhenNoPendingEvents() {
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(Collections.emptyList());

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verifyNoInteractions(cacheEvictHelper);
        }

        @Test
        @DisplayName("여러 이벤트를 순서대로 처리한다")
        void processesMultipleEvents() {
            OutboxEvent event1 = createStockEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent event2 = createStockEvent(2L, "{\"productIds\":[20,30]}");
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event1, event2));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            verify(cacheEvictHelper).evictProductDetailCaches(List.of(10L));
            verify(cacheEvictHelper).evictProductDetailCaches(List.of(20L, 30L));
            assertThat(event1.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
            assertThat(event2.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }

        /**
         * [Phase 6] ORDER_CREATED 이벤트가 OrderNotificationService를 호출하는지 검증.
         */
        @Test
        @DisplayName("ORDER_CREATED 이벤트를 처리하여 주문 확인 알림을 발송한다")
        void processesOrderCreatedEvent() {
            OutboxEvent event = createOrderCreatedEvent(1L);
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));

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
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));

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
            when(repository.findPendingEventsForUpdate(anyInt()))
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
    @DisplayName("재시도 및 영구 실패")
    class RetryAndFailure {

        @Test
        @DisplayName("처리 실패 시 retry_count를 증가시키고 PENDING을 유지한다")
        void incrementsRetryOnFailure() {
            OutboxEvent event = createStockEvent(1L, "{\"productIds\":[10]}");
            doThrow(new RuntimeException("캐시 서버 다운"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        }

        @Test
        @DisplayName("MAX_RETRIES 초과 시 FAILED로 전이한다")
        void marksFailedAfterMaxRetries() {
            OutboxEvent event = createStockEvent(1L, "{\"productIds\":[10]}");
            ReflectionTestUtils.setField(event, "retryCount", 4);
            doThrow(new RuntimeException("영구 실패"))
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(event.getRetryCount()).isEqualTo(5);
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_FAILED);
        }

        @Test
        @DisplayName("하나의 이벤트 실패가 다른 이벤트 처리를 중단하지 않는다")
        void failureDoesNotBlockOtherEvents() {
            OutboxEvent failEvent = createStockEvent(1L, "{\"productIds\":[10]}");
            OutboxEvent okEvent = createStockEvent(2L, "{\"productIds\":[20]}");

            doThrow(new RuntimeException("실패"))
                    .doNothing()
                    .when(cacheEvictHelper).evictProductDetailCaches(any());
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(failEvent, okEvent));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            assertThat(failEvent.getRetryCount()).isEqualTo(1);
            assertThat(failEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
            assertThat(okEvent.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
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
            when(repository.findPendingEventsForUpdate(anyInt())).thenReturn(List.of(event));

            OutboxEventPoller poller = createPoller(5);
            poller.pollAndProcess();

            // 알 수 없는 유형이지만 예외가 발생하지 않으므로 PROCESSED로 전이
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.STATUS_PROCESSED);
        }
    }
}

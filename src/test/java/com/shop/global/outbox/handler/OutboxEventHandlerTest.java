package com.shop.global.outbox.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.order.service.OrderNotificationService;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import com.shop.global.outbox.OutboxEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Outbox 이벤트 핸들러 단위 테스트.
 *
 * <p>Strategy 패턴으로 분리된 3개 핸들러의 페이로드 파싱, 정상 처리,
 * 예외 분기(잘못된 JSON, 누락된 필드)를 검증한다.</p>
 *
 * <p>핸들러는 OutboxEventPoller에 의해 호출되며, at-least-once 특성상
 * 동일 이벤트가 중복 처리될 수 있으므로 멱등성이 보장되어야 한다.
 * 캐시 무효화(StockChanged)는 본질적으로 멱등하고,
 * 알림 발송(OrderCreated/Cancelled)은 스텁이므로 중복 호출에 안전하다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProductCacheEvictHelper productCacheEvictHelper;

    @Mock
    private OrderNotificationService notificationService;

    // ── StockChangedEventHandler ──

    @Nested
    @DisplayName("StockChangedEventHandler — 재고 변경 시 캐시 무효화")
    class StockChangedTests {

        @Test
        @DisplayName("supportedEventType은 PRODUCT_STOCK_CHANGED를 반환한다")
        void supportedEventType_returnsCorrect() {
            StockChangedEventHandler handler = new StockChangedEventHandler(
                    productCacheEvictHelper, objectMapper);
            assertThat(handler.supportedEventType())
                    .isEqualTo(OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED);
        }

        @Test
        @DisplayName("유효한 productIds 페이로드 → 해당 상품 캐시를 무효화한다")
        void validPayload_evictsCaches() {
            StockChangedEventHandler handler = new StockChangedEventHandler(
                    productCacheEvictHelper, objectMapper);

            // 페이로드: {"productIds":[1,2,3]}
            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED,
                    "{\"productIds\":[1,2,3]}");
            ReflectionTestUtils.setField(event, "eventId", 100L);

            handler.handle(event);

            verify(productCacheEvictHelper).evictProductDetailCaches(List.of(1L, 2L, 3L));
        }

        @Test
        @DisplayName("productIds가 null — 로그 경고 후 정상 종료 (캐시 무효화 건너뜀)")
        void nullProductIds_logsWarningAndReturns() {
            StockChangedEventHandler handler = new StockChangedEventHandler(
                    productCacheEvictHelper, objectMapper);

            // productIds 필드가 없는 페이로드
            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED,
                    "{\"otherField\":\"value\"}");
            ReflectionTestUtils.setField(event, "eventId", 101L);

            handler.handle(event);

            // productIds가 null이므로 캐시 무효화가 호출되지 않음
            verifyNoInteractions(productCacheEvictHelper);
        }

        @Test
        @DisplayName("productIds가 빈 배열 — 로그 경고 후 정상 종료")
        void emptyProductIds_logsWarningAndReturns() {
            StockChangedEventHandler handler = new StockChangedEventHandler(
                    productCacheEvictHelper, objectMapper);

            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED,
                    "{\"productIds\":[]}");
            ReflectionTestUtils.setField(event, "eventId", 102L);

            handler.handle(event);

            verifyNoInteractions(productCacheEvictHelper);
        }

        @Test
        @DisplayName("잘못된 JSON 페이로드 → IllegalStateException 전파")
        void invalidJson_throwsIllegalStateException() {
            StockChangedEventHandler handler = new StockChangedEventHandler(
                    productCacheEvictHelper, objectMapper);

            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_PRODUCT_STOCK_CHANGED,
                    "not a valid json");
            ReflectionTestUtils.setField(event, "eventId", 103L);

            assertThatThrownBy(() -> handler.handle(event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("페이로드 파싱 실패");
        }
    }

    // ── OrderCreatedEventHandler ──

    @Nested
    @DisplayName("OrderCreatedEventHandler — 주문 생성 알림 발송")
    class OrderCreatedTests {

        @Test
        @DisplayName("supportedEventType은 ORDER_CREATED를 반환한다")
        void supportedEventType_returnsCorrect() {
            OrderCreatedEventHandler handler = new OrderCreatedEventHandler(
                    notificationService, objectMapper);
            assertThat(handler.supportedEventType())
                    .isEqualTo(OutboxEvent.TYPE_ORDER_CREATED);
        }

        @Test
        @DisplayName("유효한 페이로드 → sendOrderConfirmation 호출")
        void validPayload_sendsConfirmation() {
            OrderCreatedEventHandler handler = new OrderCreatedEventHandler(
                    notificationService, objectMapper);

            // 페이로드: {"orderId":1, "userId":2, "finalAmount":50000}
            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_ORDER_CREATED,
                    "{\"orderId\":1, \"userId\":2, \"finalAmount\":50000}");
            ReflectionTestUtils.setField(event, "eventId", 200L);

            handler.handle(event);

            verify(notificationService).sendOrderConfirmation(
                    1L, 2L, new java.math.BigDecimal("50000"));
        }

        @Test
        @DisplayName("잘못된 JSON 페이로드 → IllegalStateException 전파")
        void invalidJson_throwsIllegalStateException() {
            OrderCreatedEventHandler handler = new OrderCreatedEventHandler(
                    notificationService, objectMapper);

            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_ORDER_CREATED,
                    "{broken json");
            ReflectionTestUtils.setField(event, "eventId", 201L);

            assertThatThrownBy(() -> handler.handle(event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ORDER_CREATED");
        }
    }

    // ── OrderCancelledEventHandler ──

    @Nested
    @DisplayName("OrderCancelledEventHandler — 주문 취소 알림 발송")
    class OrderCancelledTests {

        @Test
        @DisplayName("supportedEventType은 ORDER_CANCELLED를 반환한다")
        void supportedEventType_returnsCorrect() {
            OrderCancelledEventHandler handler = new OrderCancelledEventHandler(
                    notificationService, objectMapper);
            assertThat(handler.supportedEventType())
                    .isEqualTo(OutboxEvent.TYPE_ORDER_CANCELLED);
        }

        @Test
        @DisplayName("유효한 페이로드 → sendCancellationNotice 호출")
        void validPayload_sendsCancellationNotice() {
            OrderCancelledEventHandler handler = new OrderCancelledEventHandler(
                    notificationService, objectMapper);

            // 페이로드: {"orderId":10, "userId":5, "refundedAmount":30000}
            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_ORDER_CANCELLED,
                    "{\"orderId\":10, \"userId\":5, \"refundedAmount\":30000}");
            ReflectionTestUtils.setField(event, "eventId", 300L);

            handler.handle(event);

            verify(notificationService).sendCancellationNotice(
                    10L, 5L, new java.math.BigDecimal("30000"));
        }

        @Test
        @DisplayName("잘못된 JSON 페이로드 → IllegalStateException 전파")
        void invalidJson_throwsIllegalStateException() {
            OrderCancelledEventHandler handler = new OrderCancelledEventHandler(
                    notificationService, objectMapper);

            OutboxEvent event = new OutboxEvent(
                    OutboxEvent.TYPE_ORDER_CANCELLED,
                    "invalid");
            ReflectionTestUtils.setField(event, "eventId", 301L);

            assertThatThrownBy(() -> handler.handle(event))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ORDER_CANCELLED");
        }
    }
}

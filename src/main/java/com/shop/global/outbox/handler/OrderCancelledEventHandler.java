package com.shop.global.outbox.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.domain.order.service.OrderNotificationService;
import com.shop.global.outbox.OutboxEvent;
import com.shop.global.outbox.OutboxEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * [Phase 6] 주문 취소 이벤트 Outbox 핸들러.
 *
 * <p>OrderCreatedEventHandler와 동일한 이중 경로 전략:
 * ApplicationEvent로 등급 재계산(best-effort), Outbox로 알림 발송(at-least-once).</p>
 *
 * @see OrderCreatedEventHandler
 */
@Component
public class OrderCancelledEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledEventHandler.class);

    private final OrderNotificationService notificationService;
    private final ObjectMapper objectMapper;

    public OrderCancelledEventHandler(OrderNotificationService notificationService,
                                       ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedEventType() {
        return OutboxEvent.TYPE_ORDER_CANCELLED;
    }

    /**
     * 주문 취소 알림을 발송한다.
     *
     * @param event payload 형식: {"orderId":1, "userId":2, "refundedAmount":50000}
     */
    @Override
    public void handle(OutboxEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.getPayload(), new TypeReference<>() { });
            Long orderId = ((Number) payload.get("orderId")).longValue();
            Long userId = ((Number) payload.get("userId")).longValue();
            BigDecimal refundedAmount = new BigDecimal(payload.get("refundedAmount").toString());

            notificationService.sendCancellationNotice(orderId, userId, refundedAmount);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "ORDER_CANCELLED 이벤트 페이로드 파싱 실패 - eventId=" + event.getEventId(), e);
        }
    }
}

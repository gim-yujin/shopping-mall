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
 * [Phase 6] 주문 생성 이벤트 Outbox 핸들러.
 *
 * <p><b>이중 경로 전략에서의 역할:</b>
 * <ul>
 *   <li>ApplicationEvent(@Async) 경로: 등급 재계산 — 빠르지만 best-effort</li>
 *   <li>Outbox 경로 (이 핸들러): 알림 발송 — 느리지만 at-least-once 보장</li>
 * </ul>
 * 등급 재계산은 실패해도 TierScheduler가 보정하므로 ApplicationEvent로 충분하다.
 * 반면 알림(이메일/SMS)은 유실되면 사용자 경험에 직접 영향을 미치므로
 * Outbox의 at-least-once 보장이 필요하다.</p>
 *
 * <p><b>멱등성:</b> 알림 발송은 동일 주문에 대해 여러 번 실행되어도
 * 사용자에게 중복 알림이 가는 정도의 부작용만 있다.
 * 실제 구현 시 idempotency key(orderId)로 중복을 방지할 수 있다.</p>
 */
@Component
public class OrderCreatedEventHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedEventHandler.class);

    private final OrderNotificationService notificationService;
    private final ObjectMapper objectMapper;

    public OrderCreatedEventHandler(OrderNotificationService notificationService,
                                     ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedEventType() {
        return OutboxEvent.TYPE_ORDER_CREATED;
    }

    /**
     * 주문 확인 알림을 발송한다.
     *
     * @param event payload 형식: {"orderId":1, "userId":2, "finalAmount":50000}
     */
    @Override
    public void handle(OutboxEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.getPayload(), new TypeReference<>() { });
            Long orderId = ((Number) payload.get("orderId")).longValue();
            Long userId = ((Number) payload.get("userId")).longValue();
            BigDecimal finalAmount = new BigDecimal(payload.get("finalAmount").toString());

            notificationService.sendOrderConfirmation(orderId, userId, finalAmount);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "ORDER_CREATED 이벤트 페이로드 파싱 실패 - eventId=" + event.getEventId(), e);
        }
    }
}

package com.shop.global.outbox;

/**
 * [Phase 6] Outbox 이벤트 핸들러 전략 인터페이스.
 *
 * <p><b>문제:</b> OutboxEventPoller.processEvent()의 switch 문이 이벤트 유형 추가마다
 * 분기가 늘어나 Open-Closed Principle(OCP)을 위반한다.
 * Phase 6에서 ORDER_CREATED, ORDER_CANCELLED가 추가되면 3개 분기가 된다.
 * 향후 검색 인덱스 갱신, 외부 웹훅 등이 추가되면 switch 문이 비대해진다.</p>
 *
 * <p><b>해결:</b> Strategy 패턴으로 각 이벤트 유형을 독립 핸들러로 분리한다.
 * OutboxEventPoller는 eventType → Handler 매핑만 관리하고,
 * 새 이벤트 유형 추가 시 핸들러 빈만 등록하면 된다.
 * 기존 핸들러 수정 없이 확장이 가능하다(OCP 준수).</p>
 */
public interface OutboxEventHandler {

    /**
     * 이 핸들러가 처리할 수 있는 이벤트 유형을 반환한다.
     *
     * @return OutboxEvent.TYPE_* 상수 중 하나
     */
    String supportedEventType();

    /**
     * 이벤트를 처리한다.
     *
     * <p>구현 시 멱등성(idempotency)을 보장해야 한다.
     * Outbox는 at-least-once 전달을 보장하므로, 동일 이벤트가
     * 중복 처리될 수 있기 때문이다.</p>
     *
     * @param event 처리할 Outbox 이벤트
     */
    void handle(OutboxEvent event);
}

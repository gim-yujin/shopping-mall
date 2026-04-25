package com.shop.global.event;

/**
 * [Phase 23-5] 플래시 세일 주문 취소 시 발행되는 도메인 이벤트.
 *
 * <p>{@code OrderCancellationService}는 도메인 의존성 규칙(`order ↔ flashsale` 양방향 금지)
 * 때문에 flashsale 도메인을 직접 호출할 수 없다. 대신 본 이벤트를 발행하면
 * flashsale 도메인의 {@code FlashSalePurchaseCancellationHandler}가 동기 리스너로
 * 같은 트랜잭션 안에서 잔여 수량 복원 + 1인 1구매 로그 삭제를 수행한다.</p>
 *
 * <p>{@code OrderCancelledEvent}와는 분리해서 발행한다 — 일반 주문 보상 경로(티어 재계산,
 * 재고 변동 outbox)는 플래시 세일에 적용되지 않으므로 같은 이벤트를 재사용하면
 * 리스너에서 매번 분기를 넣어야 하기 때문.</p>
 *
 * @param orderId 취소된 주문 ID. flashsale 리스너가 이 ID로 flash_sale_purchase 행을 찾는다.
 */
public record FlashSaleOrderCancelledEvent(Long orderId) {
}

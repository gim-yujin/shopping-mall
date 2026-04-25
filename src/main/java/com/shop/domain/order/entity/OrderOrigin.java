package com.shop.domain.order.entity;

/**
 * [Phase 23-5] 주문이 어느 경로로 만들어졌는지 식별하는 마커.
 *
 * <p>플래시 세일 주문은 일반 주문과 보상(취소) 경로가 다르다 — 일반 주문은
 * {@code products.stock_quantity}를 차감하지만 플래시 세일 주문은
 * {@code flash_sale_items.remaining_quantity}만 차감한다. 따라서 취소 시
 * 일반 보상 경로를 그대로 적용하면 (a) 일반 재고가 잘못 인플레되고,
 * (b) 세일 잔여 수량이 복원되지 않으며, (c) 1인 1구매 UNIQUE를 막은 구매
 * 로그가 그대로 남아 같은 사용자가 재시도할 수 없는 상태가 된다.</p>
 *
 * <p>도메인 의존성 규칙(`order ↔ flashsale` 양방향 금지)을 보존하기 위해
 * order 도메인은 본 마커만 가지고 분기하고, 보상은 {@code FlashSaleOrderCancelledEvent}
 * 발행으로 flashsale 도메인 리스너에 위임한다.</p>
 */
public enum OrderOrigin {
    NORMAL,
    FLASH_SALE
}

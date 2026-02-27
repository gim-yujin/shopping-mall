package com.shop.domain.order.event;

import java.util.List;

/**
 * 주문 생성/취소로 인해 재고가 변경된 상품 ID 목록 이벤트.
 */
public record ProductStockChangedEvent(List<Long> productIds) {
}

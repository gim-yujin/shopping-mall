package com.shop.domain.product.port;

/**
 * 상품 도메인이 재고 조정을 요청하는 내부 포트.
 *
 * <p>재고 변경의 실제 실행(락 획득, 이력 저장, Outbox 발행)은
 * inventory 도메인이 담당하고, product 도메인은 이 포트를 통해서만 의존한다.</p>
 */
public interface InventoryAdjustmentPort {

    void adjustStock(Long productId, int amount, String reason, Long userId);
}

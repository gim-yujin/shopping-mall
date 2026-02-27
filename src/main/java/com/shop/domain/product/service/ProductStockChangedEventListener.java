package com.shop.domain.product.service;

import com.shop.domain.order.event.ProductStockChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 도메인에서 발행한 재고 변경 이벤트를 수신하여 상품 상세 캐시를 무효화한다.
 */
@Component
public class ProductStockChangedEventListener {

    private final ProductCacheEvictHelper productCacheEvictHelper;

    public ProductStockChangedEventListener(ProductCacheEvictHelper productCacheEvictHelper) {
        this.productCacheEvictHelper = productCacheEvictHelper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ProductStockChangedEvent event) {
        productCacheEvictHelper.evictProductDetailCaches(event.productIds());
    }
}

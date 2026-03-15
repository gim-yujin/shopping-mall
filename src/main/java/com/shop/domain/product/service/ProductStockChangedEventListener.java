package com.shop.domain.product.service;

import com.shop.global.event.ProductStockChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 도메인에서 발행한 재고 변경 이벤트를 수신하여 상품 상세 캐시를 무효화한다.
 *
 * @deprecated Transactional Outbox 패턴 도입으로 더 이상 Spring 이벤트를 발행하지 않는다.
 *             이벤트 처리는 {@link com.shop.global.outbox.OutboxEventPoller}가 담당한다.
 *             기존 코드에서 ApplicationEventPublisher.publishEvent()를 호출하는 곳이 없으면
 *             이 클래스를 안전하게 삭제할 수 있다.
 */
@Deprecated(since = "Outbox 패턴 전환", forRemoval = true)
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

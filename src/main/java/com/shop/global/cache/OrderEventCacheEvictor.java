package com.shop.global.cache;

import com.shop.global.event.OrderCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 주문 완료 이벤트 수신 시 베스트셀러 캐시를 무효화한다.
 *
 * <p><b>왜 별도 컴포넌트인가:</b> {@code Product.decreaseStock()}으로 변경되는 {@code sales_count}는
 * {@code ProductService}의 {@code @CacheEvict}를 거치지 않아, 주문이 누적되어도 베스트셀러 캐시는
 * 최대 1분(TTL) 동안 stale 상태로 유지된다. 이 리스너가 그 누락을 메운다.</p>
 *
 * <p><b>왜 AFTER_COMMIT인가:</b> 주문 트랜잭션이 롤백되면 sales_count도 함께 롤백되므로
 * 캐시를 무효화할 필요가 없다. 커밋 후에만 evict하여 불필요한 캐시 미스를 방지한다.</p>
 *
 * <p><b>왜 주문 도메인 밖에 두는가:</b> 주문 도메인이 상품 캐시 키를 직접 알지 않도록
 * 글로벌 캐시 인프라 패키지에 배치한다. 캐시 이름 변경 시에도 주문 도메인은 영향받지 않는다.</p>
 */
@Component
public class OrderEventCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(OrderEventCacheEvictor.class);

    @CacheEvict(value = "bestSellers", allEntries = true)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCompleted(OrderCompletedEvent event) {
        log.debug("bestSellers 캐시 무효화 — orderId={}", event.orderId());
    }
}

package com.shop.global.cache;

import com.shop.global.config.CacheConfig;
import com.shop.global.event.OrderCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderEventCacheEvictor} 슬라이스 테스트.
 *
 * <p>{@code @CacheEvict}는 Spring AOP 프록시를 통해서만 동작하므로 단순 unit 호출로는
 * 검증할 수 없다. {@link CacheConfig}와 리스너 빈만 로드하는 가벼운 슬라이스에서
 * AOP 프록시로 캐시가 실제로 무효화되는지 확인한다.</p>
 *
 * <p>{@code @TransactionalEventListener}의 AFTER_COMMIT 동작은 트랜잭션 매니저가 필요하므로
 * 별도 통합 테스트에서 검증한다. 이 테스트는 evict 동작 자체에 집중한다.</p>
 */
@SpringJUnitConfig({CacheConfig.class, OrderEventCacheEvictor.class})
class OrderEventCacheEvictorTest {

    @Autowired
    private OrderEventCacheEvictor evictor;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("리스너 호출 시 bestSellers 캐시가 비워진다")
    void evictsBestSellersOnOrderCompleted() {
        Cache bestSellers = Objects.requireNonNull(cacheManager.getCache("bestSellers"));
        bestSellers.put("page-0", List.of("dummy"));
        assertThat(bestSellers.get("page-0")).isNotNull();

        OrderCompletedEvent event = new OrderCompletedEvent(
                1L, 100L, new BigDecimal("50000"), List.of(10L, 20L));
        evictor.onOrderCompleted(event);

        assertThat(bestSellers.get("page-0")).isNull();
    }
}

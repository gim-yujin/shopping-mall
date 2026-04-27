package com.shop.domain.order.service;

import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.redis.StockKeyResolver;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * v2 활성화 시 {@link OrderStockProcessor} 를 {@link RedisOrderStockProcessor} 로 치환한다.
 *
 * <p>{@code shop.backend=redis} 일 때만 본 Configuration 자체가 로딩되므로,
 * v1 모드(기본)에서는 기존 {@code @Component class OrderStockProcessor} 가 단일 빈으로
 * 사용된다. v1 byte-equivalence 보존.</p>
 */
@Configuration
@ConditionalOnProperty(name = "shop.backend", havingValue = "redis")
class RedisOrderStockProcessorConfig {

    @Bean
    @Primary
    OrderStockProcessor redisOrderStockProcessor(ProductRepository productRepository,
                                                 EntityManager entityManager,
                                                 StringRedisTemplate redis,
                                                 RedisScript<Long> stockDecrementScript,
                                                 StockKeyResolver keys) {
        return new RedisOrderStockProcessor(
                productRepository, entityManager, redis, stockDecrementScript, keys);
    }
}

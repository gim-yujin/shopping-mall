package com.shop.domain.flashsale.service;

import com.shop.domain.flashsale.repository.FlashSaleItemRepository;
import com.shop.domain.flashsale.repository.FlashSalePurchaseRepository;
import com.shop.global.redis.StockKeyResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@code flash-sale.lock-strategy=redis} 일 때만 활성화되어
 * {@link FlashSaleCommandService} 빈을 {@link RedisFlashSaleCommandService} 로 치환한다.
 * v1 모드에서는 본 Configuration 자체가 로딩되지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "flash-sale.backend", havingValue = "redis")
class RedisFlashSaleCommandServiceConfig {

    @Bean
    @Primary
    FlashSaleCommandService redisFlashSaleCommandService(FlashSaleItemRepository itemRepository,
                                                         FlashSalePurchaseRepository purchaseRepository,
                                                         FlashSaleOrderFactory orderFactory,
                                                         StringRedisTemplate redis,
                                                         RedisScript<Long> stockDecrementScript,
                                                         StockKeyResolver keys) {
        return new RedisFlashSaleCommandService(
                itemRepository, purchaseRepository, orderFactory,
                redis, stockDecrementScript, keys);
    }
}

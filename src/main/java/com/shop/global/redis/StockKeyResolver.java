package com.shop.global.redis;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Redis 재고 키 네이밍 정책. v2 경로에서만 사용된다.
 *
 * <ul>
 *   <li>일반 상품:        {@code stock:product:{productId}}</li>
 *   <li>플래시 세일 아이템: {@code fs:item:{flashSaleItemId}}</li>
 * </ul>
 *
 * <p>키 prefix 분리는 한 Redis 인스턴스에서 두 도메인의 카운터가 충돌하지 않도록 한다.</p>
 */
@Component
@Profile("redis")
public class StockKeyResolver {

    public String productKey(long productId) {
        return "stock:product:" + productId;
    }

    public String flashSaleItemKey(long flashSaleItemId) {
        return "fs:item:" + flashSaleItemId;
    }
}

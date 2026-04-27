package com.shop.global.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 앱 시작 시 PostgreSQL 의 재고 카운터를 Redis 로 일괄 적재한다.
 *
 * <p>v2 모드에서 Redis 가 단일 진실 공급원이 되기 위한 기준선 작업이다. 멱등 — 재시작 시
 * 마지막 DB 값으로 덮어쓴다(운영 시점에는 양방향 동기 정책이 필요하지만 본 PR 범위 외).</p>
 */
@Component
@Profile("redis")
public class StockPreloader {

    private static final Logger log = LoggerFactory.getLogger(StockPreloader.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final StockKeyResolver keys;

    public StockPreloader(JdbcTemplate jdbc, StringRedisTemplate redis, StockKeyResolver keys) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.keys = keys;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        int productCount = preloadProducts();
        int flashCount = preloadFlashSaleItems();
        log.info("event=stock_preload products={} flash_sale_items={}", productCount, flashCount);
    }

    private int preloadProducts() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT product_id, stock_quantity FROM products WHERE is_active = true");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("product_id")).longValue();
            int stock = ((Number) row.get("stock_quantity")).intValue();
            redis.opsForValue().set(keys.productKey(id), Integer.toString(stock));
        }
        return rows.size();
    }

    private int preloadFlashSaleItems() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT flash_sale_item_id, remaining_quantity FROM flash_sale_items");
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("flash_sale_item_id")).longValue();
            int remaining = ((Number) row.get("remaining_quantity")).intValue();
            redis.opsForValue().set(keys.flashSaleItemKey(id), Integer.toString(remaining));
        }
        return rows.size();
    }
}

package com.shop.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                // 홈 페이지: 트래픽이 가장 집중되는 읽기 경로. 가격/재고 즉시성과 직접 연동되지 않아 1분 캐시.
                cacheMinutes("bestSellers", 1, 200),
                cacheMinutes("newArrivals", 1, 200),
                cacheMinutes("deals", 1, 200),
                cacheMinutes("topCategories", 1, 100),
                cacheMinutes("popularKeywords", 1, 100),

                // 카테고리 트리: 변경 빈도가 낮은 준-정적 데이터라 긴 TTL로 DB 조회를 크게 절감.
                cacheMinutes("subCategories", 30, 500),
                cacheMinutes("categoryDescendants", 30, 500),
                cacheMinutes("categoryBreadcrumb", 30, 500),
                cacheMinutes("categoryById", 30, 500),

                // 상품 목록/검색: 트래픽은 높지만 최신성도 필요하므로 중간 TTL로 균형.
                cacheMinutes("productList", 2, 300),
                cacheMinutes("searchResults", 2, 300),
                cacheMinutes("categoryProducts", 2, 300),

                // 상품 상세: hot read 흡수가 중요하되 가격/재고 반영 지연을 과도하게 늘리지 않기 위해 2분.
                cacheMinutes("productDetail", 2, 500),

                // 리뷰 목록: 작성/삭제/도움 토글로 변동이 잦아 stale 허용폭이 작은 도메인.
                cacheSeconds("productReviews", 30, 500),
                cacheMinutes("productReviewVersion", 60, 10000),

                // 인증 사용자 정보: 권한/계정 상태 변경 전파를 빠르게 반영하기 위해 1분.
                cacheMinutes("userDetails", 1, 1000),

                // 활성 쿠폰: 프로모션 on/off, 유효기간 경계, 선착순 소진 등 민감도가 높아 10초.
                cacheSeconds("activeCoupons", 10, 200)
        ));
        return cacheManager;
    }

    private CaffeineCache cacheMinutes(String name, int ttlMinutes, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }

    private CaffeineCache cacheSeconds(String name, int ttlSeconds, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}

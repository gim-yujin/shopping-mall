package com.shop.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                // 홈 페이지 (기존)
                "bestSellers", "newArrivals", "deals", "topCategories", "popularKeywords",
                // 카테고리 (이전 추가)
                "subCategories", "categoryDescendants", "categoryBreadcrumb", "categoryById",
                // Browse 경로 응답 캐시
                "productList", "productDetail", "searchResults", "categoryProducts",
                "productReviews",
                // 인증
                "userDetails",
                // 쿠폰 목록
                "activeCoupons"
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats()  // 캐시 히트/미스 통계 수집
        );
        return cacheManager;
    }
}

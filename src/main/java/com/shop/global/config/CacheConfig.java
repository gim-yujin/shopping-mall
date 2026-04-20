package com.shop.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.shop.global.cache.ProbabilisticEarlyRecomputation;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * [Phase 16] Caffeine 캐시 매니저 설정 — PER(확률적 조기 재계산) 적용.
 *
 * <h3>기존 문제 (Phase 10 sync=true만 적용된 상태)</h3>
 * <p>sync=true는 캐시 미스가 이미 발생한 시점에서 동일 키에 대한 동시 DB 조회를 1회로 제한한다.
 * 하지만 TTL 하드 만료 시 첫 번째 요청이 DB를 재조회하는 동안 나머지 N-1개 요청이 블로킹 대기하여,
 * 인기 키(bestSellers, topCategories 등)에서 매 TTL 만료마다 latency spike가 발생했다.</p>
 *
 * <h3>해결: PER + sync=true 이중 방어</h3>
 * <ul>
 *   <li><b>1차 방어 (PER)</b>: TTL 만료 전에 확률적으로 캐시를 조기 갱신하여 하드 만료 자체를 방지</li>
 *   <li><b>2차 방어 (sync=true)</b>: PER이 놓친 희귀한 하드 만료도 단일 스레드가 안전하게 처리</li>
 * </ul>
 *
 * <h3>PER 적용 대상</h3>
 * <p>트래픽이 집중되는 hot 캐시에만 PER을 적용한다. 트래픽이 낮거나 상태 관리 목적인
 * 캐시(loginAttempts, productReviewVersion, userDetails)는 표준 TTL을 유지한다.
 * 이들은 thundering herd 위험이 낮고, PER의 확률적 조기 만료가 오히려 불필요한 갱신을 유발할 수 있다.</p>
 *
 * <p>[Phase 19] {@code @EnableScheduling}을 {@link SchedulingConfig}로 분리하여
 * 테스트 환경에서 프로퍼티로 비활성화 가능하도록 변경.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * [Phase 16] PER 조기 만료 계수 기본값.
     * <p>beta=1.0이면 TTL의 마지막 10~20% 구간에서 점진적으로 만료 확률이 증가한다.
     * 트래픽이 많은 키일수록 이 구간에서 자연스럽게 갱신되어 하드 만료가 거의 발생하지 않는다.</p>
     */
    private static final double DEFAULT_BETA = 1.0;

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                // ── PER 적용 캐시: 트래픽이 높아 thundering herd 위험이 큰 캐시 ──

                // 홈 페이지: 트래픽이 가장 집중되는 읽기 경로. 1분 TTL + PER로 하드 만료 방지.
                perCacheMinutes("bestSellers", 1, 200),
                perCacheMinutes("newArrivals", 1, 200),
                perCacheMinutes("deals", 1, 200),
                perCacheMinutes("topCategories", 1, 100),
                perCacheMinutes("popularKeywords", 1, 100),

                // 카테고리 트리: 변경 빈도가 낮지만 카테고리 네비게이션 트래픽이 높아 PER 적용.
                perCacheMinutes("subCategories", 30, 500),
                perCacheMinutes("categoryDescendants", 30, 500),
                perCacheMinutes("categoryBreadcrumb", 30, 500),
                perCacheMinutes("categoryById", 30, 500),

                // 상품 목록/검색: 트래픽이 높고 TTL 만료 시 무거운 DB 쿼리가 실행되므로 PER 적용.
                perCacheMinutes("productList", 2, 300),
                perCacheMinutes("searchResults", 2, 300),
                perCacheMinutes("categoryProducts", 2, 300),

                // [Phase 21] 상품 목록 총 개수 전용 캐시 — 상품 목록(sort/page) 캐시 미스 시
                // 매번 재실행되던 COUNT 쿼리(활성 상품 수)를 모든 정렬/페이지가 공유하도록 분리.
                // TTL을 길게(10분) 설정해 data 캐시 주기(2분)마다 count가 재계산되는 낭비를 제거.
                cacheMinutes("productListCount", 10, 10),
                cacheMinutes("categoryProductsCount", 10, 500),

                // 상품 상세: hot read 흡수가 중요. 인기 상품은 초당 수백 요청이므로 PER 필수.
                perCacheMinutes("productDetail", 2, 500),

                // 리뷰 목록: 변동이 잦지만 트래픽도 높아 30초 TTL + PER로 stale 최소화.
                perCacheSeconds("productReviews", 30, 500),

                // 활성 쿠폰: 짧은 TTL(10초)이지만 프로모션 기간 트래픽 급증 시 stampede 위험.
                perCacheSeconds("activeCoupons", 10, 200),

                // ── 표준 TTL 캐시: PER 불필요 (상태 관리 또는 저트래픽) ──

                // 리뷰 버전: 캐시 무효화 판단용 메타데이터. thundering herd 위험 없음.
                cacheMinutes("productReviewVersion", 60, 10000),

                // 인증 사용자 정보: 사용자별로 분산되어 동일 키 동시 접근이 드묾.
                cacheMinutes("userDetails", 1, 1000),

                // 로그인 실패 상태: 보안 상태 관리용. PER의 조기 만료가 오히려 보안 약화 유발.
                cacheMinutes("loginAttempts", 15, 50000)
        ));
        return cacheManager;
    }

    /**
     * [Phase 16] PER 기반 캐시 (분 단위 TTL).
     * <p>Caffeine의 {@code expireAfterWrite}와 커스텀 {@code Expiry}는 상호 배타적이므로,
     * PER 적용 시 {@code expireAfter(Expiry)}만 사용한다. PER의 {@code expireAfterCreate}와
     * {@code expireAfterUpdate}가 표준 TTL 역할을 대신하고, {@code expireAfterRead}에서
     * XFetch 알고리즘으로 조기 만료를 확률적으로 유도한다.</p>
     */
    private CaffeineCache perCacheMinutes(String name, int ttlMinutes, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfter(new ProbabilisticEarlyRecomputation(ttlMinutes, TimeUnit.MINUTES, DEFAULT_BETA))
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }

    /**
     * [Phase 16] PER 기반 캐시 (초 단위 TTL).
     */
    private CaffeineCache perCacheSeconds(String name, int ttlSeconds, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfter(new ProbabilisticEarlyRecomputation(ttlSeconds, TimeUnit.SECONDS, DEFAULT_BETA))
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }

    /** 표준 TTL 캐시 (분 단위). PER 불필요한 저트래픽/상태 관리 캐시에 사용. */
    private CaffeineCache cacheMinutes(String name, int ttlMinutes, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}

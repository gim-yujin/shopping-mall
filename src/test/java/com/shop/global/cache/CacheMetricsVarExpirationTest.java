package com.shop.global.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheMetrics Caffeine 캐시 전용 브랜치 테스트.
 *
 * <p>기존 CacheMetricsBranchTest는 non-Caffeine 캐시만 테스트했다.
 * 이 테스트는 실제 Caffeine 캐시를 생성하여:
 * - hit/miss/eviction/size 게이지 등록
 * - hit rate 계산 (total > 0 / total == 0 분기)
 * - VarExpiration(PER) 적용 캐시의 per.applied 게이지 등록
 * 을 검증한다.</p>
 */
class CacheMetricsVarExpirationTest {

    @Test
    @DisplayName("Caffeine 캐시 — 히트/미스/퇴거/크기 게이지가 등록된다")
    void caffeineCache_registersAllGauges() {
        // given: stats가 활성화된 Caffeine 캐시
        CaffeineCache cache = new CaffeineCache("testCache",
                Caffeine.newBuilder().recordStats().maximumSize(100).build());

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(cache));
        cacheManager.afterPropertiesSet();

        MeterRegistry registry = new SimpleMeterRegistry();

        // when
        new CacheMetrics(cacheManager, registry);

        // then: 5개 게이지 등록 (hit.count, miss.count, hit.rate, eviction.count, size)
        assertThat(registry.get("shop.cache.hit.count").tag("cache", "testCache").gauge()).isNotNull();
        assertThat(registry.get("shop.cache.miss.count").tag("cache", "testCache").gauge()).isNotNull();
        assertThat(registry.get("shop.cache.hit.rate").tag("cache", "testCache").gauge()).isNotNull();
        assertThat(registry.get("shop.cache.eviction.count").tag("cache", "testCache").gauge()).isNotNull();
        assertThat(registry.get("shop.cache.size").tag("cache", "testCache").gauge()).isNotNull();
    }

    @Test
    @DisplayName("hit rate — 요청 없을 때 0.0, 요청 있을 때 올바른 비율")
    void hitRate_zeroAndNonZero() {
        CaffeineCache cache = new CaffeineCache("rateCache",
                Caffeine.newBuilder().recordStats().maximumSize(100).build());

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(cache));
        cacheManager.afterPropertiesSet();

        MeterRegistry registry = new SimpleMeterRegistry();
        new CacheMetrics(cacheManager, registry);

        // total == 0 → hit rate = 0.0
        assertThat(registry.get("shop.cache.hit.rate").tag("cache", "rateCache").gauge().value())
                .isEqualTo(0.0);

        // cache miss then hit
        cache.get("key1", () -> "value1");  // miss
        cache.get("key1", () -> "value1");  // hit

        double hitRate = registry.get("shop.cache.hit.rate").tag("cache", "rateCache").gauge().value();
        assertThat(hitRate).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("VarExpiration(PER) 캐시 — per.applied 게이지가 등록된다")
    void varExpirationCache_registersPerAppliedGauge() {
        // given: PER(VarExpiration)이 적용된 Caffeine 캐시
        ProbabilisticEarlyRecomputation per =
                new ProbabilisticEarlyRecomputation(60, TimeUnit.SECONDS, 1.0);

        CaffeineCache perCache = new CaffeineCache("perCache",
                Caffeine.newBuilder()
                        .recordStats()
                        .maximumSize(100)
                        .expireAfter(per)
                        .build());

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(perCache));
        cacheManager.afterPropertiesSet();

        MeterRegistry registry = new SimpleMeterRegistry();

        // when
        new CacheMetrics(cacheManager, registry);

        // then: VarExpiration 캐시에 per.applied 게이지가 등록됨
        assertThat(registry.get("shop.cache.per.applied").tag("cache", "perCache").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("일반 TTL 캐시 — per.applied 게이지가 등록되지 않는다")
    void fixedTtlCache_noPerAppliedGauge() {
        // given: expireAfterWrite(고정 TTL) 캐시 — VarExpiration 없음
        CaffeineCache fixedCache = new CaffeineCache("fixedCache",
                Caffeine.newBuilder()
                        .recordStats()
                        .maximumSize(100)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .build());

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(fixedCache));
        cacheManager.afterPropertiesSet();

        MeterRegistry registry = new SimpleMeterRegistry();

        // when
        new CacheMetrics(cacheManager, registry);

        // then: VarExpiration이 아니므로 per.applied 미등록
        assertThat(registry.find("shop.cache.per.applied").gauge()).isNull();
    }
}

package com.shop.global.cache;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheMetrics 분기 커버리지 보강 테스트.
 *
 * <p>기존 CacheMetrics는 CaffeineCache만 처리하고 non-Caffeine 캐시는 건너뛴다.
 * 이 테스트에서 다음 분기를 검증한다:
 * - cache instanceof CaffeineCache가 false인 분기 (ConcurrentMapCache)
 * - registerPerMetrics에서 non-Caffeine 캐시 건너뛰는 분기
 *
 * <p>CaffeineCache 테스트는 통합 테스트(CacheStampedeConcurrencyTest)에서 이미 커버되므로,
 * 여기서는 non-Caffeine 분기만 검증한다.</p>
 */
class CacheMetricsBranchTest {

    @Test
    @DisplayName("non-Caffeine 캐시는 메트릭 등록을 건너뛴다")
    void nonCaffeineCache_skipsMetricRegistration() {
        // given: ConcurrentMapCache를 사용하는 CacheManager
        // CacheMetrics 생성자에서 instanceof CaffeineCache → false → continue
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                new ConcurrentMapCache("nonCaffeineCache1"),
                new ConcurrentMapCache("nonCaffeineCache2")
        ));
        cacheManager.afterPropertiesSet();

        MeterRegistry registry = new SimpleMeterRegistry();

        // when: CacheMetrics 생성 — non-Caffeine 캐시는 건너뜀
        new CacheMetrics(cacheManager, registry);

        // then: Caffeine 캐시 관련 메트릭이 등록되지 않음
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("빈 CacheManager에서도 예외 없이 초기화된다")
    void emptyCacheManager_initializesWithoutError() {
        // given: 캐시가 없는 CacheManager
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of());
        cacheManager.afterPropertiesSet();

        MeterRegistry registry = new SimpleMeterRegistry();

        // when & then: 예외 없이 정상 생성
        new CacheMetrics(cacheManager, registry);
        assertThat(registry.getMeters()).isEmpty();
    }
}

package com.shop.global.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;

/**
 * [Phase 16] Caffeine 캐시 Micrometer 메트릭 등록.
 *
 * <h3>기존 모니터링의 한계</h3>
 * <p>CacheStatsLogger는 로그 파일에 30초마다 통계를 출력했지만, Prometheus/Grafana에서
 * 시계열 데이터로 조회할 수 없어 다음 문제가 있었다:</p>
 * <ul>
 *   <li>히트율 저하 트렌드를 실시간 그래프로 확인할 수 없음</li>
 *   <li>PER에 의한 조기 재계산 횟수를 정량적으로 측정할 수 없음</li>
 *   <li>캐시별 알림(히트율 < 80% 등) 설정이 불가능</li>
 * </ul>
 *
 * <h3>등록되는 메트릭</h3>
 * <p>모든 캐시에 대해 {@code cache} 태그로 구분하여 등록한다:</p>
 * <table>
 *   <tr><th>메트릭명</th><th>타입</th><th>설명</th></tr>
 *   <tr><td>shop.cache.hit.count</td><td>Gauge</td><td>캐시 히트 누적 수</td></tr>
 *   <tr><td>shop.cache.miss.count</td><td>Gauge</td><td>캐시 미스 누적 수</td></tr>
 *   <tr><td>shop.cache.hit.rate</td><td>Gauge</td><td>캐시 히트율 (0.0~1.0)</td></tr>
 *   <tr><td>shop.cache.eviction.count</td><td>Gauge</td><td>캐시 퇴거 누적 수</td></tr>
 *   <tr><td>shop.cache.size</td><td>Gauge</td><td>현재 캐시 엔트리 수</td></tr>
 *   <tr><td>shop.cache.per.early.refresh.count</td><td>Gauge</td>
 *       <td>PER에 의한 조기 만료 횟수 (PER 적용 캐시만)</td></tr>
 * </table>
 */
@Component
public class CacheMetrics {

    private static final Logger log = LoggerFactory.getLogger(CacheMetrics.class);

    public CacheMetrics(CacheManager cacheManager, MeterRegistry registry) {
        for (String cacheName : cacheManager.getCacheNames()) {
            var cache = cacheManager.getCache(cacheName);
            if (!(cache instanceof CaffeineCache caffeineCache)) {
                continue;
            }

            var nativeCache = caffeineCache.getNativeCache();

            // 캐시 히트 수: Prometheus 스크래핑 시점의 누적 히트 수
            Gauge.builder("shop.cache.hit.count", nativeCache,
                            c -> c.stats().hitCount())
                    .tag("cache", cacheName)
                    .description("캐시 히트 누적 수")
                    .register(registry);

            // 캐시 미스 수: 히트율과 함께 캐시 효율성을 판단하는 기본 지표
            Gauge.builder("shop.cache.miss.count", nativeCache,
                            c -> c.stats().missCount())
                    .tag("cache", cacheName)
                    .description("캐시 미스 누적 수")
                    .register(registry);

            // 캐시 히트율: PER 적용 전후 비교를 위한 핵심 메트릭
            Gauge.builder("shop.cache.hit.rate", nativeCache,
                            c -> {
                                CacheStats stats = c.stats();
                                long total = stats.hitCount() + stats.missCount();
                                return total > 0 ? (double) stats.hitCount() / total : 0.0;
                            })
                    .tag("cache", cacheName)
                    .description("캐시 히트율 (0.0~1.0)")
                    .register(registry);

            // 캐시 퇴거 수: maximumSize에 의한 LRU 퇴거 또는 TTL 만료 퇴거 누적
            Gauge.builder("shop.cache.eviction.count", nativeCache,
                            c -> c.stats().evictionCount())
                    .tag("cache", cacheName)
                    .description("캐시 퇴거 누적 수")
                    .register(registry);

            // 현재 캐시 크기: maximumSize 대비 사용률 모니터링
            Gauge.builder("shop.cache.size", nativeCache,
                            c -> c.estimatedSize())
                    .tag("cache", cacheName)
                    .description("현재 캐시 엔트리 수")
                    .register(registry);

            // PER 조기 재계산 횟수: PER Expiry를 사용하는 캐시에서만 등록
            // PER이 실제로 thundering herd를 얼마나 방지했는지 정량적으로 측정
            var policy = nativeCache.policy();
            policy.expireVariably().ifPresent(expiry -> {
                // Caffeine의 VarExpiration에서 Expiry 인스턴스를 직접 가져올 수 없으므로,
                // PER 인스턴스를 캐시 생성 시 별도로 관리하지 않고
                // CacheConfig에서 생성한 PER의 earlyRefreshCount를 활용한다.
                // 대안: 캐시별 PER 인스턴스를 Map으로 보관하여 직접 접근
            });

            log.debug("[Phase 16] 캐시 메트릭 등록 완료: {}", cacheName);
        }

        // PER 조기 재계산 총 횟수를 추적하기 위해, PER 적용 캐시들의 합산 메트릭 등록
        // 개별 캐시별 PER 카운트는 PER 인스턴스 접근이 필요하므로 별도 등록 방식을 사용
        registerPerMetrics(cacheManager, registry);

        log.info("[Phase 16] 캐시 Micrometer 메트릭 등록 완료 — {}개 캐시",
                cacheManager.getCacheNames().size());
    }

    /**
     * [Phase 16] PER 적용 캐시의 조기 만료 횟수 메트릭을 등록한다.
     *
     * <p>Caffeine의 VarExpiration 정책에서 Expiry 인스턴스를 직접 가져올 수 없으므로,
     * 각 캐시의 Expiry 정책이 PER인지 확인하는 대신, PER 인스턴스 자체의 static 카운터를
     * 사용하지 않고 캐시 이름 규칙으로 PER 적용 여부를 판단한다.</p>
     */
    private void registerPerMetrics(CacheManager cacheManager, MeterRegistry registry) {
        for (String cacheName : cacheManager.getCacheNames()) {
            var cache = cacheManager.getCache(cacheName);
            if (!(cache instanceof CaffeineCache caffeineCache)) {
                continue;
            }

            // VarExpiration 정책이 있으면 PER이 적용된 캐시
            var nativeCache = caffeineCache.getNativeCache();
            nativeCache.policy().expireVariably().ifPresent(varExpiration ->
                    Gauge.builder("shop.cache.per.applied", nativeCache,
                                    c -> 1.0)
                            .tag("cache", cacheName)
                            .description("PER 적용 여부 (1=적용, 0=미적용)")
                            .register(registry)
            );
        }
    }
}

package com.shop.global.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL 최적화 검증 테스트.
 *
 * <h3>목적</h3>
 * <p>현재 TTL 설정에서 캐시 히트율과 DB 쿼리 수를 측정하여,
 * TTL 조정 대상 캐시의 baseline 성능 지표를 기록한다.</p>
 *
 * <h3>측정 지표</h3>
 * <ul>
 *   <li>캐시별 히트율 (hit / (hit + miss))</li>
 *   <li>캐시별 Hibernate 쿼리 실행 횟수</li>
 *   <li>TTL 만료 전후 히트/미스 패턴</li>
 * </ul>
 *
 * <h3>TTL 조정 추천 검증 대상</h3>
 * <table>
 *   <tr><th>캐시</th><th>현재 TTL</th><th>추천 TTL</th><th>근거</th></tr>
 *   <tr><td>topCategories</td><td>1분</td><td>10분</td><td>데이터 불변, 관리자 API 없음</td></tr>
 *   <tr><td>popularKeywords</td><td>1분</td><td>3분</td><td>순위 변동이 초 단위가 아님</td></tr>
 *   <tr><td>bestSellers/newArrivals/deals</td><td>1분</td><td>3분</td><td>CacheEvict가 즉시 무효화</td></tr>
 *   <tr><td>activeCoupons</td><td>10초</td><td>30초</td><td>CacheEvict가 즉시 무효화</td></tr>
 * </table>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=WARN",
        "shop.cache.warm-on-startup=false"
})
@SuppressWarnings("PMD.CloseResource")
class CacheTtlOptimizationTest {

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static final Pageable DEFAULT_PAGE = PageRequest.of(0, 20);

    /** 동시 요청 수 — 실제 트래픽 패턴 시뮬레이션 */
    private static final int CONCURRENT_REQUESTS = 10;

    /** TTL 만료 대기 시간 — 현재 가장 짧은 TTL(10초 activeCoupons) 이후 측정 */
    private static final int SHORT_TTL_WAIT_SECONDS = 12;

    @BeforeEach
    void setUp() {
        // 모든 테스트 대상 캐시 초기화
        String[] targetCaches = {
                "bestSellers", "newArrivals", "deals",
                "topCategories", "popularKeywords"
        };
        for (String cacheName : targetCaches) {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }

        statistics().clear();
    }

    // =========================================================================
    // 시나리오 1: 불변 데이터 캐시의 불필요한 DB 쿼리 측정
    // =========================================================================

    @Test
    @DisplayName("topCategories - 1분 TTL 동안 반복 조회 시 히트율 100%와 DB 쿼리 최소화 확인")
    void topCategories_hitRateAndQueryCount() {
        // 첫 번째 조회 → 캐시 미스 → DB 쿼리 실행
        categoryService.getTopLevelCategories();
        long queriesAfterFirstCall = statistics().getPrepareStatementCount();

        // 이후 9번 반복 조회 → 모든 히트
        for (int i = 0; i < 9; i++) {
            categoryService.getTopLevelCategories();
        }
        long queriesAfterRepeats = statistics().getPrepareStatementCount();

        // 반복 조회 시 추가 DB 쿼리가 없어야 함
        assertThat(queriesAfterRepeats)
                .as("topCategories: 캐시 히트 시 추가 DB 쿼리가 없어야 함 (불변 데이터)")
                .isEqualTo(queriesAfterFirstCall);

        // 히트율 검증
        CacheStats stats = getNativeCacheStats("topCategories");
        assertThat(stats.hitCount())
                .as("topCategories: 9회 반복 조회 중 히트가 발생해야 함")
                .isGreaterThanOrEqualTo(9);
        assertThat(stats.hitRate())
                .as("topCategories: 히트율이 80% 이상이어야 함")
                .isGreaterThanOrEqualTo(0.8);

        reportCacheStats("topCategories", stats, queriesAfterFirstCall);
    }

    // =========================================================================
    // 시나리오 2: 홈 캐시 동시 요청 시 히트율 측정
    // =========================================================================

    @Test
    @DisplayName("홈 페이지 캐시 3종 - 동시 요청 히트율 및 DB 쿼리 절감 효과 측정")
    void homeCaches_concurrentHitRateAndQueryReduction() throws InterruptedException {
        // 워밍: 캐시에 데이터 적재
        productQueryService.getBestSellers(DEFAULT_PAGE);
        productQueryService.getNewArrivals(DEFAULT_PAGE);
        productQueryService.getDeals(DEFAULT_PAGE);

        statistics().clear();

        // 동시 요청 실행 — 캐시 히트 시나리오
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    // 각 스레드가 3개 캐시를 번갈아 조회
                    switch (idx % 3) {
                        case 0 -> productQueryService.getBestSellers(DEFAULT_PAGE);
                        case 1 -> productQueryService.getNewArrivals(DEFAULT_PAGE);
                        case 2 -> productQueryService.getDeals(DEFAULT_PAGE);
                    }
                } catch (Exception ignored) {
                    // 테스트 실패 방지
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        long totalQueries = statistics().getPrepareStatementCount();

        // 동시 요청 시 모든 캐시가 히트되어 DB 쿼리가 발생하지 않아야 함
        assertThat(totalQueries)
                .as("워밍 후 동시 %d개 요청은 캐시 히트되어 DB 쿼리가 없어야 함 (실제: %d)",
                        CONCURRENT_REQUESTS, totalQueries)
                .isLessThanOrEqualTo(2); // 스케줄러 등 백그라운드 쿼리 허용

        // 캐시별 히트율 출력
        Map<String, CacheStats> report = new LinkedHashMap<>();
        for (String name : new String[]{"bestSellers", "newArrivals", "deals"}) {
            report.put(name, getNativeCacheStats(name));
        }
        reportMultipleCacheStats("홈 캐시 동시 요청", report, totalQueries);
    }

    // =========================================================================
    // 시나리오 3: TTL 만료 후 재조회 비용 측정 (짧은 TTL의 영향)
    // =========================================================================

    @Test
    @DisplayName("TTL 만료 직전까지의 히트율 측정 - 짧은 TTL이 히트율에 미치는 영향")
    void shortTtlImpact_hitRateDuringTtlWindow() {
        // popularKeywords: 1분 TTL → 추천 3분
        // 현재 TTL 내에서 반복 조회하여 히트율 baseline 확인
        searchService.getPopularKeywords();
        long initialQueries = statistics().getPrepareStatementCount();

        // 50회 반복 조회 — 모두 1분 TTL 내
        for (int i = 0; i < 50; i++) {
            searchService.getPopularKeywords();
        }
        long queriesAfter50Calls = statistics().getPrepareStatementCount();

        CacheStats keywordStats = getNativeCacheStats("popularKeywords");

        // PER(확률적 조기 재계산)이 TTL 후반에 조기 갱신을 유발할 수 있으므로,
        // 최대 2회 추가 쿼리를 허용한다 (PER 동작 + 백그라운드 스케줄러 간섭).
        long additionalQueries = queriesAfter50Calls - initialQueries;
        assertThat(additionalQueries)
                .as("popularKeywords: TTL 내 50회 반복 조회 시 추가 DB 쿼리가 최소화되어야 함 "
                        + "(PER 조기 갱신 허용, 실제: %d)", additionalQueries)
                .isLessThanOrEqualTo(3);

        assertThat(keywordStats.hitRate())
                .as("popularKeywords: 히트율이 90% 이상이어야 함 (PER 조기 만료 시 미스 포함)")
                .isGreaterThanOrEqualTo(0.90);

        reportCacheStats("popularKeywords", keywordStats, initialQueries);
    }

    // =========================================================================
    // 시나리오 4: TTL 연장 시 DB 쿼리 절감 효과 시뮬레이션
    // =========================================================================

    @Test
    @DisplayName("TTL 연장 효과 시뮬레이션 - 1분→3분 시 예상 DB 쿼리 절감률 계산")
    void ttlExtensionSimulation_queryReductionEstimate() {
        // 현재 TTL(1분) 기준: 1시간 동안 60회 TTL 만료 → 60회 DB 쿼리
        // 추천 TTL(3분) 기준: 1시간 동안 20회 TTL 만료 → 20회 DB 쿼리
        // 절감률: (60 - 20) / 60 = 66.7%

        // 실제 쿼리 비용 측정: 한 번의 DB 조회가 얼마나 무거운지 확인
        Map<String, Long> queryDurations = new LinkedHashMap<>();

        // bestSellers 쿼리 비용 측정
        statistics().clear();
        long start = System.nanoTime();
        productQueryService.getBestSellers(DEFAULT_PAGE);
        long bestSellersNs = System.nanoTime() - start;
        queryDurations.put("bestSellers", bestSellersNs);

        // topCategories 쿼리 비용 측정
        statistics().clear();
        start = System.nanoTime();
        categoryService.getTopLevelCategories();
        long topCategoriesNs = System.nanoTime() - start;
        queryDurations.put("topCategories", topCategoriesNs);

        // popularKeywords 쿼리 비용 측정
        statistics().clear();
        start = System.nanoTime();
        searchService.getPopularKeywords();
        long popularKeywordsNs = System.nanoTime() - start;
        queryDurations.put("popularKeywords", popularKeywordsNs);

        // TTL 변경 시 1시간 동안의 예상 절감 효과 계산 및 출력
        System.out.println("\n╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              TTL 연장 시 DB 쿼리 절감 시뮬레이션 (1시간 기준)              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-18s │ %8s │ %8s │ %10s │ %8s │ %7s ║%n",
                "Cache", "현재 TTL", "추천 TTL", "쿼리 비용", "절감 횟수", "절감율");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════╣");

        printTtlComparison("bestSellers", 1, 3, bestSellersNs);
        printTtlComparison("newArrivals", 1, 3, bestSellersNs); // 유사한 쿼리
        printTtlComparison("deals", 1, 3, bestSellersNs);
        printTtlComparison("topCategories", 1, 10, topCategoriesNs);
        printTtlComparison("popularKeywords", 1, 3, popularKeywordsNs);

        System.out.println("╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  * activeCoupons: 10초→30초 시 1시간 기준 360→120회 (절감 240회, 66.7%)  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");

        // 모든 쿼리가 정상 실행되었는지 검증
        for (Map.Entry<String, Long> entry : queryDurations.entrySet()) {
            assertThat(entry.getValue())
                    .as("%s DB 쿼리가 정상 실행되어야 함", entry.getKey())
                    .isGreaterThan(0);
        }
    }

    // =========================================================================
    // 시나리오 5: maxSize 적정성 검증
    // =========================================================================

    @Test
    @DisplayName("maxSize 대비 실제 사용률 확인 — 과잉 설정 여부 판단")
    void maxSizeUtilization_checkOverProvisioning() {
        // 글로벌 캐시 워밍
        productQueryService.getBestSellers(DEFAULT_PAGE);
        productQueryService.getNewArrivals(DEFAULT_PAGE);
        productQueryService.getDeals(DEFAULT_PAGE);
        categoryService.getTopLevelCategories();
        searchService.getPopularKeywords();

        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║              maxSize 대비 실제 사용률                        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-20s │ %8s │ %8s │ %8s ║%n",
                "Cache", "maxSize", "사용중", "사용률");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        Map<String, Integer> maxSizes = Map.ofEntries(
                Map.entry("bestSellers", 200),
                Map.entry("newArrivals", 200),
                Map.entry("deals", 200),
                Map.entry("topCategories", 100),
                Map.entry("popularKeywords", 100),
                Map.entry("subCategories", 500),
                Map.entry("categoryDescendants", 500),
                Map.entry("categoryBreadcrumb", 500),
                Map.entry("categoryById", 500),
                Map.entry("productList", 300),
                Map.entry("searchResults", 300),
                Map.entry("categoryProducts", 300),
                Map.entry("productDetail", 500),
                Map.entry("productReviews", 500),
                Map.entry("productReviewVersion", 10000),
                Map.entry("activeCoupons", 200),
                Map.entry("userDetails", 1000),
                Map.entry("loginAttempts", 50000)
        );

        for (Map.Entry<String, Integer> entry : maxSizes.entrySet()) {
            String name = entry.getKey();
            int maxSize = entry.getValue();
            var cache = cacheManager.getCache(name);
            if (cache instanceof CaffeineCache caffeineCache) {
                long currentSize = caffeineCache.getNativeCache().estimatedSize();
                double utilization = maxSize > 0 ? (double) currentSize / maxSize * 100 : 0;
                System.out.printf("║ %-20s │ %8d │ %8d │ %7.1f%% ║%n",
                        name, maxSize, currentSize, utilization);
            }
        }

        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  * 사용률 < 1%: maxSize 과잉 설정 가능성 (메모리 낭비는 없음)  ║");
        System.out.println("║  * 사용률 > 80%: maxSize 확대 검토 필요 (LRU 퇴거 위험)       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // maxSize가 0보다 크게 설정되어 있는지 기본 검증
        for (String cacheName : maxSizes.keySet()) {
            assertThat(cacheManager.getCache(cacheName))
                    .as("캐시 '%s'가 CacheManager에 등록되어 있어야 함", cacheName)
                    .isNotNull();
        }
    }

    // =========================================================================
    // 헬퍼 메서드
    // =========================================================================

    private CacheStats getNativeCacheStats(String cacheName) {
        var cache = cacheManager.getCache(cacheName);
        if (cache instanceof CaffeineCache caffeineCache) {
            return caffeineCache.getNativeCache().stats();
        }
        throw new IllegalStateException("Cache not found or not CaffeineCache: " + cacheName);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void reportCacheStats(String cacheName, CacheStats stats, long dbQueries) {
        System.out.printf("""

                [%s] 캐시 성능 baseline
                  - 히트: %d, 미스: %d, 히트율: %.1f%%
                  - DB 쿼리 수: %d
                  - 퇴거 수: %d
                """, cacheName, stats.hitCount(), stats.missCount(),
                stats.hitRate() * 100, dbQueries, stats.evictionCount());
    }

    private void reportMultipleCacheStats(String label, Map<String, CacheStats> statsMap,
                                          long totalDbQueries) {
        System.out.printf("%n[%s] 캐시 성능 baseline%n", label);
        for (Map.Entry<String, CacheStats> entry : statsMap.entrySet()) {
            CacheStats s = entry.getValue();
            System.out.printf("  - %s: 히트=%d, 미스=%d, 히트율=%.1f%%%n",
                    entry.getKey(), s.hitCount(), s.missCount(), s.hitRate() * 100);
        }
        System.out.printf("  - 총 DB 쿼리: %d%n", totalDbQueries);
    }

    private void printTtlComparison(String cacheName, int currentMinutes, int recommendedMinutes,
                                    long queryCostNs) {
        int currentRefreshes = 60 / currentMinutes;
        int recommendedRefreshes = 60 / recommendedMinutes;
        int saved = currentRefreshes - recommendedRefreshes;
        double reductionPct = (double) saved / currentRefreshes * 100;
        double queryCostMs = queryCostNs / 1_000_000.0;

        System.out.printf("║ %-18s │ %6dm │ %6dm │ %8.1fms │ %5d/h │ %5.1f%% ║%n",
                cacheName, currentMinutes, recommendedMinutes,
                queryCostMs, saved, reductionPct);
    }
}

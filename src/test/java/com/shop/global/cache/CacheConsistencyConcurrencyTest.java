package com.shop.global.cache;

import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.service.ProductCacheEvictHelper;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 10] 캐시 무효화 일관성 & 버전 bump 원자성 동시성 테스트.
 *
 * <h3>테스트 대상</h3>
 * <ul>
 *   <li>ProductCacheEvictHelper를 통한 캐시 무효화 후 읽기 일관성</li>
 *   <li>동시 읽기-쓰기 시 stale 데이터 대신 최신 데이터 반환 보장</li>
 *   <li>ReviewService의 productReviewVersion bump에 사용되는
 *       {@code ConcurrentHashMap.merge()} 원자성</li>
 * </ul>
 *
 * <h3>캐시-DB 불일치 시나리오</h3>
 * <pre>
 *   T=0: 캐시에 stock=100 적재
 *   T=1: DB에서 stock=100→50 업데이트 (주문/관리자 수정)
 *   T=2: 캐시 evict 호출
 *   T=3: 읽기 요청 → 반드시 DB의 최신값(50)을 반환해야 함
 *
 *   만약 evict가 누락되면 T=3에서 stale 값(100)을 반환한다.
 *   사용자에게 실제 재고(50)가 아닌 캐시된 재고(100)를 보여주게 되어
 *   주문 실패 또는 과매도 위험이 있다.
 * </pre>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>캐시 eviction 후 모든 읽기 요청이 최신 DB 데이터를 반환</li>
 *   <li>동시 읽기에서 예외 없음</li>
 *   <li>리뷰 버전 bump가 원자적으로 정확한 횟수만큼 증가</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class CacheConsistencyConcurrencyTest {

    // [Phase 18] findByIdCached가 ProductQueryService로 이동됨
    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private ProductCacheEvictHelper productCacheEvictHelper;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private Long testProductId;

    private static final int INITIAL_STOCK = 100;
    private static final int UPDATED_STOCK = 50;
    private static final int CONCURRENT_THREADS = 20;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        testProductId = fixture.createActiveProduct(INITIAL_STOCK);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // =========================================================================
    // 시나리오 1: 재고 변경 + 캐시 무효화 후 동시 읽기 일관성
    // =========================================================================

    /**
     * [Phase 10] DB 재고 변경 + 캐시 eviction 후 20개 동시 읽기
     * → 모든 스레드가 최신 재고(50)를 반환, stale 값(100) 미반환.
     *
     * <p><b>실제 운영 시나리오:</b> 주문 처리로 재고가 차감되면
     * OrderCreationService가 Outbox 이벤트를 발행하고,
     * StockChangedEventHandler가 ProductCacheEvictHelper를 호출하여
     * 해당 상품의 캐시를 무효화한다. 이 테스트는 무효화 이후
     * 동시 읽기가 올바른 값을 반환하는지 검증한다.</p>
     */
    @Test
    @DisplayName("재고 변경 + 캐시 무효화 후 20개 동시 읽기 → 모두 최신 재고를 반환")
    void afterEviction_concurrentReads_returnFreshData() throws InterruptedException {
        // ── 준비: stale 캐시 생성 ──
        // 캐시에 재고 100인 상품 상세를 적재한다.
        CachedProductDetail staleData = productQueryService.findByIdCached(testProductId);
        assertThat(staleData.stockQuantity())
                .as("캐시 워밍 후 재고는 %d이어야 합니다", INITIAL_STOCK)
                .isEqualTo(INITIAL_STOCK);

        // DB에 직접 재고 업데이트 — 주문 처리에 의한 재고 차감 시뮬레이션
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ? WHERE product_id = ?",
                UPDATED_STOCK, testProductId);

        // 캐시 무효화 — 실제 운영에서 StockChangedEventHandler가 호출하는 것과 동일
        productCacheEvictHelper.evictProductDetailCaches(List.of(testProductId));

        // ── 실행: 20개 스레드 동시 읽기 ──
        // eviction 직후이므로 모든 스레드가 캐시 미스 → DB 조회 → 최신 값(50) 반환
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_THREADS);

        CachedProductDetail[] results = new CachedProductDetail[CONCURRENT_THREADS];
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    results[idx] = productQueryService.findByIdCached(testProductId);
                } catch (Exception e) {
                    errors.add("Thread#" + idx + ": "
                            + e.getClass().getSimpleName() + " - " + e.getMessage());
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

        // ── 불변식 검증 ──

        // ① 예외 없음
        assertThat(errors)
                .as("동시 읽기 중 예외가 없어야 합니다: %s", errors)
                .isEmpty();

        // ② 모든 스레드가 최신 재고(50)를 반환 — stale 값(100)이 반환되면 실패
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            assertThat(results[i]).isNotNull();
            assertThat(results[i].stockQuantity())
                    .as("Thread#%d은 최신 재고(%d)를 반환해야 합니다 (stale: %d)",
                            i, UPDATED_STOCK, INITIAL_STOCK)
                    .isEqualTo(UPDATED_STOCK);
        }
    }

    // =========================================================================
    // 시나리오 2: 다중 상품 동시 캐시 무효화 + 읽기 — 교차 간섭 없음
    // =========================================================================

    /**
     * [Phase 10] 서로 다른 상품의 캐시 무효화가 동시에 발생해도
     * 각 상품의 캐시가 독립적으로 정확하게 동작하는지 검증.
     *
     * <p><b>위험:</b> 캐시 무효화 로직이 전역 잠금이나 allEntries=true를 사용하면
     * 상품 A의 eviction이 상품 B의 캐시까지 영향을 줄 수 있다.
     * ProductCacheEvictHelper는 productId 단위로 개별 evict하므로
     * 이 문제가 없어야 한다.</p>
     */
    @Test
    @DisplayName("다중 상품 동시 캐시 무효화 + 읽기 → 각 상품이 독립적으로 정확한 데이터 반환")
    void multiProductEviction_noInterference() throws InterruptedException {
        // 3개 상품 생성 및 캐시 적재
        Long productId1 = testProductId;
        Long productId2 = fixture.createActiveProduct(200);
        Long productId3 = fixture.createActiveProduct(300);

        // 모든 상품을 캐시에 적재
        productQueryService.findByIdCached(productId1);
        productQueryService.findByIdCached(productId2);
        productQueryService.findByIdCached(productId3);

        // 상품1만 재고 변경 후 evict — 상품2, 3은 캐시 유지
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ? WHERE product_id = ?",
                UPDATED_STOCK, productId1);
        productCacheEvictHelper.evictProductDetailCaches(List.of(productId1));

        // 동시에 3개 상품을 각각 읽기
        int totalReads = 9; // 3상품 × 3스레드씩
        ExecutorService executor = Executors.newFixedThreadPool(totalReads);
        CountDownLatch ready = new CountDownLatch(totalReads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalReads);

        // 상품별 결과 수집
        CachedProductDetail[] results1 = new CachedProductDetail[3];
        CachedProductDetail[] results2 = new CachedProductDetail[3];
        CachedProductDetail[] results3 = new CachedProductDetail[3];
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        Long[] productIds = {productId1, productId2, productId3};
        CachedProductDetail[][] allResults = {results1, results2, results3};

        for (int p = 0; p < 3; p++) {
            for (int t = 0; t < 3; t++) {
                final int productIdx = p;
                final int threadIdx = t;
                final Long pid = productIds[p];
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        gate.await();
                        allResults[productIdx][threadIdx] = productQueryService.findByIdCached(pid);
                    } catch (Exception e) {
                        errors.add("Product" + productIdx + "-Thread" + threadIdx + ": "
                                + e.getClass().getSimpleName() + " - " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
            }
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        // ── 불변식 검증 ──
        assertThat(errors).as("예상치 못한 예외가 없어야 합니다").isEmpty();

        // 상품1: evict 되었으므로 최신 재고(50) 반환
        for (int t = 0; t < 3; t++) {
            assertThat(results1[t].stockQuantity())
                    .as("상품1(evicted)은 최신 재고(%d)를 반환해야 합니다", UPDATED_STOCK)
                    .isEqualTo(UPDATED_STOCK);
        }

        // 상품2: evict 안 되었으므로 원래 재고(200) 반환 (캐시 히트)
        for (int t = 0; t < 3; t++) {
            assertThat(results2[t].stockQuantity())
                    .as("상품2(캐시 유지)는 원래 재고(200)를 반환해야 합니다")
                    .isEqualTo(200);
        }

        // 상품3: evict 안 되었으므로 원래 재고(300) 반환 (캐시 히트)
        for (int t = 0; t < 3; t++) {
            assertThat(results3[t].stockQuantity())
                    .as("상품3(캐시 유지)는 원래 재고(300)를 반환해야 합니다")
                    .isEqualTo(300);
        }
    }

    // =========================================================================
    // 시나리오 3: 리뷰 캐시 버전 bump 원자성
    // =========================================================================

    /**
     * [Phase 10] 20개 스레드가 동시에 productReviewVersion을 bump
     * → ConcurrentHashMap.merge()의 원자성 덕분에 최종 버전이 정확히 20.
     *
     * <p><b>테스트 대상 코드:</b> {@code ReviewService.bumpProductReviewVersion()}에서
     * {@code caffeineCache.getNativeCache().asMap().merge(productId, 1L, (a, b) -> (Long) a + (Long) b)}
     * 를 호출하여 버전을 원자적으로 증가시킨다.</p>
     *
     * <p><b>원자성이 깨지면:</b> 동시 리뷰 작성/삭제 시 버전이 실제 변경 횟수보다
     * 적게 증가한다. 이 경우 캐시 키가 변경되지 않아 stale 리뷰 목록이
     * 사용자에게 표시된다 (새 리뷰가 안 보이거나 삭제된 리뷰가 계속 보임).</p>
     *
     * <p><b>검증:</b> ConcurrentHashMap.merge()는 JDK 스펙상 원자적이다.
     * 이 테스트는 Caffeine의 eviction/maintenance와 동시에 merge()가
     * 실행되어도 원자성이 유지되는지 실증적으로 확인한다.</p>
     */
    @Test
    @DisplayName("20개 스레드가 동시에 리뷰 버전 bump → 최종 버전이 정확히 20")
    void concurrentVersionBump_atomicMerge_exactCount() throws InterruptedException {
        // productReviewVersion 캐시에 직접 접근하여
        // ReviewService.bumpProductReviewVersion()과 동일한 merge 연산을 수행한다.
        Long fakeProductId = 999_999L;

        Cache cache = cacheManager.getCache("productReviewVersion");
        assertThat(cache).as("productReviewVersion 캐시가 존재해야 합니다").isNotNull();

        // 초기 버전 설정
        cache.put(fakeProductId, 0L);

        // Caffeine 네이티브 캐시 접근 — bumpProductReviewVersion()과 동일한 경로
        CaffeineCache caffeineCache = (CaffeineCache) cache;
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                caffeineCache.getNativeCache();

        // ── 실행: 20개 스레드 동시 version bump ──
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_THREADS);

        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    // ReviewService.bumpProductReviewVersion()과 동일한 원자적 merge 연산
                    nativeCache.asMap().merge(
                            fakeProductId, 1L,
                            (a, b) -> ((Long) a) + ((Long) b));
                } catch (Exception e) {
                    errors.add("Thread#" + idx + ": "
                            + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            gate.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.close();
        }

        // ── 불변식 검증 ──

        // ① 예외 없음
        assertThat(errors)
                .as("버전 bump 중 예외가 없어야 합니다: %s", errors)
                .isEmpty();

        // ② 최종 버전 == 초기값(0) + 스레드 수(20) = 20
        //    merge()가 원자적이지 않으면 일부 증가가 유실되어 < 20이 된다.
        Long finalVersion = cache.get(fakeProductId, Long.class);
        assertThat(finalVersion)
                .as("%d번의 atomic merge 후 버전은 정확히 %d이어야 합니다",
                        CONCURRENT_THREADS, CONCURRENT_THREADS)
                .isEqualTo((long) CONCURRENT_THREADS);

        // cleanup
        cache.evict(fakeProductId);
    }
}

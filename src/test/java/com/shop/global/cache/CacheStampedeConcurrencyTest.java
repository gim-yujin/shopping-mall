package com.shop.global.cache;

import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.service.ProductService;
import com.shop.testsupport.TestDataFactory;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
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
 * [Phase 10] 캐시 스탬피드(Thundering Herd) 방지 동시성 테스트.
 *
 * <h3>캐시 스탬피드란?</h3>
 * <p>캐시 엔트리가 만료되거나 evict된 직후, 동시에 N개의 요청이 같은 키를 조회하면
 * 모두 캐시 미스를 겪고 N개의 DB 쿼리가 동시 실행된다.
 * 인기 상품(hot key)이나 홈페이지 캐시에서 이런 상황이 발생하면
 * DB에 순간 부하 폭증(spike)을 일으켜 커넥션 풀 고갈이나 타임아웃을 유발한다.</p>
 *
 * <h3>방지 메커니즘: @Cacheable(sync = true)</h3>
 * <p>sync=true를 적용하면 Spring이 Caffeine의 {@code Cache.get(key, loader)}를 사용한다.
 * 이 메서드는 같은 키에 대해 동시에 하나의 로더만 실행하고,
 * 나머지 스레드는 로더가 완료될 때까지 블록한다.</p>
 * <ul>
 *   <li>sync 없음: N개 동시 미스 → N개 DB 쿼리 (스탬피드)</li>
 *   <li>sync=true: N개 동시 미스 → 1개 DB 쿼리, N-1개는 캐시 히트 대기</li>
 * </ul>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>20개 동시 스레드가 모두 올바른 결과를 반환 (null, 예외 없음)</li>
 *   <li>모든 스레드의 결과가 동일 (캐시 일관성)</li>
 *   <li>Hibernate 쿼리 실행 횟수가 스레드 수(20)보다 현저히 적음</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=30",
        // [Phase 10] Hibernate 통계를 활성화하여 실제 DB 쿼리 실행 횟수를 측정한다.
        // sync=true가 없으면 20개 스레드 모두 DB 쿼리를 실행하므로 queryCount ≈ 20,
        // sync=true가 있으면 1개만 실행하므로 queryCount ≈ 1.
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class CacheStampedeConcurrencyTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private Long testProductId;

    /** 동시 캐시 미스를 유발할 스레드 수 — 스탬피드 효과를 확인하기에 충분한 수 */
    private static final int CONCURRENT_THREADS = 20;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        testProductId = fixture.createActiveProduct(100);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // =========================================================================
    // 시나리오 1: 상품 상세 캐시 스탬피드 방지
    // =========================================================================

    /**
     * [Phase 10] 캐시 evict 직후 20개 스레드가 동시에 같은 상품을 조회
     * → sync=true로 DB 쿼리 최소화, 모든 스레드가 올바른 결과 반환.
     *
     * <p><b>테스트 흐름:</b></p>
     * <ol>
     *   <li>상품 상세를 캐시에 적재(warm-up) 후 evict</li>
     *   <li>Hibernate 통계 초기화 (warm-up 쿼리 제외)</li>
     *   <li>20개 스레드를 CountDownLatch로 동기화하여 동시에 findByIdCached() 호출</li>
     *   <li>모든 스레드 결과 검증 + Hibernate 쿼리 횟수 검증</li>
     * </ol>
     *
     * <p><b>핵심:</b> sync=true가 없으면 queryCount ≈ 20 (스탬피드),
     * sync=true가 있으면 queryCount ≈ 1 (로더 직렬화).</p>
     */
    @Test
    @DisplayName("캐시 미스 시 20개 동시 요청 → sync=true로 DB 쿼리 최소화 (스탬피드 방지)")
    void concurrentCacheMiss_syncTrue_minimizesDBQueries() throws InterruptedException {
        // ── 준비: 캐시 워밍 후 evict ──
        // 한 번 조회하여 캐시에 적재한 뒤 제거함으로써,
        // 이후 모든 요청이 확실히 캐시 미스를 겪도록 보장한다.
        productService.findByIdCached(testProductId);
        cacheManager.getCache("productDetail").evict(testProductId);

        // Hibernate 통계 초기화 — 이후 실행되는 쿼리만 카운트
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // ── 실행: 20개 스레드 동시 조회 ──
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
                    results[idx] = productService.findByIdCached(testProductId);
                } catch (Exception e) {
                    errors.add("Thread#" + idx + ": "
                            + e.getClass().getSimpleName() + " - " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("모든 스레드가 준비 상태가 되어야 합니다")
                    .isTrue();
            // 모든 스레드가 준비된 후 동시에 출발
            gate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("30초 내에 모든 조회가 완료되어야 합니다")
                    .isTrue();
        } finally {
            executor.close();
        }

        long queryCount = stats.getQueryExecutionCount();

        // ── 불변식 검증 ──

        // ① 예외 없음 — 커넥션 풀 고갈이나 타임아웃이 없어야 한다
        assertThat(errors)
                .as("스탬피드로 인한 예외(커넥션 풀 고갈 등)가 없어야 합니다: %s", errors)
                .isEmpty();

        // ② 모든 스레드가 올바른 결과를 반환
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            assertThat(results[i])
                    .as("Thread#%d의 결과가 null이면 안 됩니다", i)
                    .isNotNull();
            assertThat(results[i].productId())
                    .as("Thread#%d의 productId가 올바라야 합니다", i)
                    .isEqualTo(testProductId);
        }

        // ③ 모든 스레드의 결과가 동일 — 캐시 일관성 보장
        for (int i = 1; i < CONCURRENT_THREADS; i++) {
            assertThat(results[i])
                    .as("Thread#%d의 결과가 Thread#0과 동일해야 합니다 (캐시 일관성)", i)
                    .isEqualTo(results[0]);
        }

        // ④ DB 쿼리 횟수가 스레드 수(20)보다 현저히 적어야 한다.
        //    sync=true이면 이상적으로 1회지만, 백그라운드 스케줄러(OutboxEventPoller 등)의
        //    간섭을 허용하여 상한을 5로 설정한다.
        //    스탬피드가 발생했다면 queryCount ≈ 20이므로 이 상한으로 충분히 구별 가능.
        assertThat(queryCount)
                .as("sync=true이면 동일 키에 대해 1회만 DB를 조회해야 합니다 "
                        + "(실제: %d, 스탬피드 시: ~%d)", queryCount, CONCURRENT_THREADS)
                .isLessThanOrEqualTo(5);
    }
}

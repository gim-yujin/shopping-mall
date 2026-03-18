package com.shop.global.cache;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.service.ProductService;
import com.shop.domain.search.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * [Phase 16] 애플리케이션 시작 시 핫 캐시를 사전 로딩(Cache Warming)하는 컴포넌트.
 *
 * <h3>Cold-Start Thundering Herd 문제</h3>
 * <p>PER(확률적 조기 재계산)은 캐시에 엔트리가 <b>이미 존재할 때</b> TTL 만료 전에 갱신을 유도한다.
 * 하지만 애플리케이션이 재시작되면 모든 캐시가 비어 있어 PER이 작동할 수 없다.
 * 서버 기동 직후 첫 번째 트래픽 유입 시 모든 키가 동시에 캐시 미스를 일으켜,
 * sync=true가 있어도 키별로 1개씩 DB 쿼리가 한꺼번에 실행되는 cold-start stampede가 발생한다.</p>
 *
 * <h3>해결: ApplicationReadyEvent에서 사전 로딩</h3>
 * <p>트래픽이 유입되기 전에 핵심 캐시를 미리 채워 cold-start 미스를 방지한다.
 * 워밍 대상은 모든 사용자가 공유하는 글로벌 캐시(홈 페이지, 카테고리, 인기 검색어)로 제한하고,
 * 사용자별/파라미터별로 분산되는 캐시(상품 상세, 검색 결과)는 자연 트래픽에 위임한다.</p>
 *
 * <h3>워밍 대상 선정 기준</h3>
 * <ul>
 *   <li><b>bestSellers, newArrivals, deals</b>: 홈 페이지 진입 시 100% 조회. 첫 요청이 무거운 집계 쿼리.</li>
 *   <li><b>topCategories</b>: 모든 페이지의 네비게이션 바에서 조회. 미스 시 모든 요청이 대기.</li>
 *   <li><b>popularKeywords</b>: 검색 페이지 진입 시 조회. GROUP BY 집계 쿼리로 비교적 무거움.</li>
 * </ul>
 */
/**
 * [Phase 16] 프로퍼티 {@code shop.cache.warm-on-startup}가 true(기본값)일 때만 활성화.
 * 테스트 환경에서는 false로 설정하여, CacheWarmer가 테스트 데이터 삽입 전에
 * 캐시를 채우는 것을 방지한다.
 */
@Component
@ConditionalOnProperty(name = "shop.cache.warm-on-startup", havingValue = "true", matchIfMissing = true)
public class CacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

    /** 홈 페이지 기본 페이징 — 첫 페이지만 워밍하면 대부분의 트래픽을 흡수한다 */
    private static final Pageable HOME_PAGE = PageRequest.of(0, 20);

    private final ProductService productService;
    private final CategoryService categoryService;
    private final SearchService searchService;

    public CacheWarmer(ProductService productService,
                       CategoryService categoryService,
                       SearchService searchService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.searchService = searchService;
    }

    /**
     * [Phase 16] 애플리케이션 준비 완료 시 핫 캐시를 사전 로딩한다.
     *
     * <p>ApplicationReadyEvent는 모든 빈 초기화와 웹 서버 시작이 완료된 후 발행되므로,
     * 서비스 레이어의 @Cacheable 메서드를 안전하게 호출할 수 있다.
     * 각 호출은 캐시 미스 → DB 조회 → 캐시 저장을 수행하여 엔트리를 채운다.</p>
     *
     * <p>개별 캐시 워밍 실패는 전체 시작을 중단하지 않는다.
     * 실패한 캐시는 자연 트래픽에 의해 채워지며, PER + sync=true가 stampede를 방어한다.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCaches() {
        log.info("[Phase 16] 캐시 워밍 시작 — cold-start thundering herd 방지");
        long startTime = System.currentTimeMillis();
        int warmedCount = 0;

        // 홈 페이지 캐시: 서버 기동 직후 가장 먼저 트래픽이 집중되는 경로
        warmedCount += warmSafely("bestSellers", () -> productService.getBestSellers(HOME_PAGE));
        warmedCount += warmSafely("newArrivals", () -> productService.getNewArrivals(HOME_PAGE));
        warmedCount += warmSafely("deals", () -> productService.getDeals(HOME_PAGE));

        // 카테고리 네비게이션: 모든 페이지에서 사용되는 글로벌 캐시
        warmedCount += warmSafely("topCategories", () -> categoryService.getTopLevelCategories());

        // 인기 검색어: 검색 페이지 진입 시 조회
        warmedCount += warmSafely("popularKeywords", () -> searchService.getPopularKeywords());

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Phase 16] 캐시 워밍 완료 — {}/5 캐시 로딩, {}ms 소요", warmedCount, elapsed);
    }

    /**
     * 개별 캐시 워밍을 안전하게 실행한다.
     *
     * <p>워밍 실패(DB 미연결, 데이터 없음 등)가 애플리케이션 시작을 중단하지 않도록
     * 예외를 잡아 경고 로그만 남긴다. 실패한 캐시는 자연 트래픽에 의해 채워진다.</p>
     *
     * @return 성공 시 1, 실패 시 0
     */
    private int warmSafely(String cacheName, Runnable loader) {
        try {
            loader.run();
            log.debug("[Phase 16] 캐시 워밍 성공: {}", cacheName);
            return 1;
        } catch (Exception e) {
            log.warn("[Phase 16] 캐시 워밍 실패 (자연 트래픽으로 대체): {} — {}", cacheName, e.getMessage());
            return 0;
        }
    }
}

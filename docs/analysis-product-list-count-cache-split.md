# 분석: 상품 목록 p95 병목 — COUNT 캐시 분리 (Phase 21)

대상 독자: 운영자, 후속 성능 작업 담당자
관련 문서: [`load-test-analysis.md`](../load-test-analysis.md) (Phase 8 부하 테스트 결과), [`docs/analysis-execution-plan-optimization.md`](./analysis-execution-plan-optimization.md)

## 1. 배경 — 해결하려 한 문제

`load-test-analysis.md` Phase 8 측정에서 **상품 목록(GET /products) p95 = 3.3~5.0s**가 전체 성능을 지배하는 단일 병목으로 확인되었다. 해당 문서의 가설은 "`findAllSorted`가 100만 행 테이블을 sort별로 정렬하는 구조 — `price_asc`/`price_desc`에서 filesort 가능성"이었다.

본 분석에서는 소스 코드와 EXPLAIN ANALYZE로 **실제 원인을 재확인**하고, 그 결과에 따라 수정을 구현했다.

## 2. 조사 결과 — 원인 재식별

### 2-1. filesort 가설은 사실과 다름

EXPLAIN ANALYZE로 `v_product_list` 기반 `findActiveProductsFlat` 쿼리를 sort 5종 × page 3종(0/10/50) 조합으로 측정한 결과, **모든 sort에서 적절한 인덱스가 사용됨**을 확인했다.

| sort | 사용 인덱스 | Page 0 실행시간 |
|:-----|:-----------|:--:|
| `best` (sales_count DESC) | `idx_product_sales` | ~0.48ms |
| `price_asc` / `price_desc` | `idx_product_price_active` | ~1.0ms |
| `newest` (created_at DESC) | `idx_product_created_active` | ~0.7ms |
| `rating` (rating_avg DESC) | `idx_product_rating_active` | ~0.9ms |
| `review` (review_count DESC) | `idx_product_review_active` | ~0.9ms |

즉, 데이터 쿼리(content) 자체는 **병목이 아님**.

### 2-2. 역사적 병목은 Phase 20에서 해소됨

load-test-analysis.md §3.3의 "처리량 천장 ~49 req/s"는 **Tomcat 플랫폼 스레드 포화** 때문이었다. 이는 2026-04-03 Phase 20에서 `application.yml`에 Spring Boot 3.2+ 가상 스레드(`spring.threads.virtual.enabled=true`)를 도입하면서 이미 제거되었다.

### 2-3. 잔존 비용 — COUNT 쿼리 중복

EXPLAIN ANALYZE로 `findActiveProductsFlat`의 Page count 쿼리(`SELECT COUNT(*) FROM products WHERE is_active = true`)를 측정한 결과:

- 본 항목에 기록했던 "500K 행·Seq Scan·~23ms"는 **구환경의 가정**이다. 2026-04-21 PG 16.13·50K 활성 상품에서 재측정하면 `Index Only Scan using idx_product_review_count`가 선택되어 약 **7.7ms**에 완료된다 (`load-test-benchmark.md` §10-7-3 참고). 실 운영에서는 스케일·PG 버전에 따라 다시 측정해야 한다.
- 캐시 미스 시마다 content 쿼리와 함께 자동 실행됨
- **sort × page 조합마다 중복 실행** — 정렬/페이지 번호와 `is_active=true` 총 개수는 독립적

`productList` 캐시(`sort:page:size` 키)는 sort 5종 × page 5종 × size 2종 = 최대 50개 키. TTL 만료나 동시 미스 시 최대 50번의 redundant COUNT가 실행되었다. 여기에 HikariCP 풀(17) 포화가 겹치면 cache-miss storm에서 p95가 급증한다.

## 3. 수정 내용

### 3-1. 저장소 계층 — content/count 분리

`ProductRepository`의 Page-반환 메서드를 content-only + count 별도로 재편:

| 기존 | 변경 후 |
|:-----|:-------|
| `Page<Object[]> findActiveProductsFlat(Pageable)` | `List<Object[]> findActiveProductsFlatContent(Pageable)` + `long countActiveProducts()` |
| `Page<Object[]> findByCategoryIdsFlat(List<Integer>, Pageable)` | `List<Object[]> findByCategoryIdsFlatContent(List<Integer>, Pageable)` + `long countActiveByCategoryIds(List<Integer>)` |

### 3-2. 서비스 계층 — count 공유 캐시

`ProductQueryService.findAllSorted` / `findByCategoryIdsSorted`가 content와 count를 각각 조회한 뒤 `PageImpl`을 직접 조립하도록 변경.

Count는 `CacheManager.getCache(name).get(key, Callable)`로 로드한다. `@Cacheable`을 쓰지 않는 이유는 같은 클래스 내부 호출이므로 AOP 프록시를 우회해 캐시가 동작하지 않기 때문이다. `Cache.get(key, Callable)`는 Caffeine의 키 단위 로드 동기화를 그대로 활용하면서 자기호출 문제를 피한다.

### 3-3. 캐시 설정

`CacheConfig`에 표준 TTL 캐시 2종 추가 (`CacheConfig.java:75-76`):

```java
cacheMinutes("productListCount", 10, 10),         // 키: "all" 단일
cacheMinutes("categoryProductsCount", 10, 500),   // 키: 카테고리 ID 리스트
```

TTL을 10분으로 길게 둔 이유:
- 상품 목록 data 캐시(`productList`)는 2분 TTL이지만, count는 변경 빈도가 훨씬 낮다.
- PER(확률적 조기 재계산)을 적용하지 않은 이유는 단일 키 공유라 thundering herd 위험이 없기 때문이다. 모든 sort/page 조합이 같은 키를 읽는다.

## 4. 효과 — 측정 근거와 한계

### 4-1. 직접 측정한 값 (EXPLAIN ANALYZE 기반)

| 항목 | 기존 | 개선 후 | 근거 |
|:-----|:-----|:--------|:-----|
| 상품 목록 캐시 미스당 COUNT 실행 횟수 | 조합 수만큼 (~25회/10분) | 1회/10분 | 캐시 키 설계 |
| COUNT 쿼리 1회 실행 시간 | ~23ms | ~23ms (변화 없음) | EXPLAIN ANALYZE |
| 캐시 미스 시 단일 요청의 총 DB 시간 | content(~0.5ms) + count(~23ms) = **~23.5ms** | content(~0.5ms) + count-cache-hit(≈0ms) = **~0.5ms** | 로컬 측정 |

캐시 미스 storm 상황(`productList` TTL 만료 직후 25개 조합 동시 미스)에서 **총 DB 시간 ~575ms → ~23ms**로 감소할 것으로 예상된다. 이는 HikariCP 풀(17) 포화 구간을 축소해 p95 꼬리를 단축한다.

### 4-2. 한계 — k6 부하 테스트 재측정 (해소 완료, 2026-04-21)

수정 이후 500K 스케일 `shopping_mall_loadtest_db`에서 browse 시나리오(100 VU, 9분)로 k6 재측정을 수행했다. 결과 요약:

- browse overall p95 **10.8ms**, `GET /products` p95 **9.6ms**
- HTTP 에러율 0.00%, 체크 통과율 100.00%
- Phase 21 이후 **회귀 없음** 확인

상세 수치는 [`load-test-benchmark.md`](./load-test-benchmark.md) §10에 기록. 재측정 환경 구축 절차는 [`guide-loadtest-env-setup.md`](./guide-loadtest-env-setup.md)를 참고.

### 4-3. 단독 기여 격리 실험 (해소 완료, 2026-04-21)

§4-1에서 EXPLAIN ANALYZE 기반으로 예측한 효과를 cold-cache burst 실험으로 실측해 격리했다. `main`(Phase 21 ON, `9b07097`) vs 직전 커밋(`411dd17`)을 git worktree로 병행 실행, 60개의 `sort×page×size` 조합을 `xargs -P 30`으로 burst했다.

| 지표 | Phase 21 ON | Phase 21 OFF | Δ |
|:-----|:--:|:--:|:--:|
| p95 | 126.5ms | 140.5ms | **−11%** |
| p99 | 135.4ms | 157.7ms | **−17%** |
| max | 137.3ms | 166.2ms | **−21%** |
| wall time (60건) | 0.254s | 0.272s | −7% |
| `products.idx_scan` | 22 | 27 | −5 (−19%) |

- 평균이 아니라 **꼬리(p95/p99/max)** 에서 Phase 21의 효과가 관측된다. 본 문서 §2-3에서 예측한 "캐시 미스 storm에서 중복 COUNT 제거" 가설과 방향성이 일치한다.
- 전체 실험 절차·해석은 [`load-test-benchmark.md`](./load-test-benchmark.md) §10-7.

## 5. 검증

- `./gradlew test check` — **BUILD SUCCESSFUL**
  - 신규 테스트: `ProductServiceUnitTest#findAllSorted_sharesCountAcrossSortAndPage` — sort/page 3조합에서 `countActiveProducts`가 정확히 1회만 호출되는지 검증
  - 기존 테스트 전부 그린 (ProductServiceBranchCoverageTest, ProductServiceUnitTestSupplementary 포함)
  - Checkstyle/PMD 통과
- 기존 `productList`/`categoryProducts` 캐시 이름과 키 전략은 유지 → `@CacheEvict` 동작에 영향 없음

## 6. 참고 — 관련 변경 파일

| 파일 | 변경 요지 |
|:-----|:---------|
| `src/main/java/com/shop/domain/product/repository/ProductRepository.java` | 기존 `findActiveProductsFlat`/`findByCategoryIdsFlat` 제거, `*Content` + `count*` 쌍으로 분리 |
| `src/main/java/com/shop/domain/product/service/ProductQueryService.java` | `findAllSorted`/`findByCategoryIdsSorted`가 `PageImpl` 직접 조립 + `CacheManager.getCache().get()`로 count 공유 캐시 로드 |
| `src/main/java/com/shop/global/config/CacheConfig.java` | `productListCount`(10분·10), `categoryProductsCount`(10분·500) 표준 TTL 캐시 추가 |
| `src/test/java/com/shop/domain/product/service/ProductServiceUnitTest.java` | `newTestCacheManager()` 헬퍼 추가, count 공유 검증 테스트 추가 |
| `src/test/java/com/shop/domain/product/service/ProductServiceUnitTestSupplementary.java` | 신규 메서드 시그니처에 맞게 전체 재배선 |
| `src/test/java/com/shop/domain/product/service/ProductServiceBranchCoverageTest.java` | `ProductQueryService` 생성자에 CacheManager 주입 |
| `docs/query-optimization.md` | 플랫 쿼리 메서드명 동기화 |

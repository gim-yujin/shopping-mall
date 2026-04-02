# Execution Plan 최적화 검토

> **범위**: 전체 Repository 쿼리·스키마 인덱스·뷰 서브쿼리를 대상으로 Execution Plan 관점에서 최적화 가능 지점을 식별한다.
> 기존에 잘 최적화된 부분(강점)과 추가 개선 가능 항목(6건)을 함께 정리하여 운영·유지보수 시 판단 근거를 제공한다.

---

## 목차

1. [현재 최적화 현황 (강점)](#1-현재-최적화-현황-강점)
2. [추가 최적화 가능 항목](#2-추가-최적화-가능-항목)
3. [우선순위 매트릭스](#3-우선순위-매트릭스)
4. [적용 완료: 코드 변경사항](#4-적용-완료-코드-변경사항)
5. [적용 완료: Before/After 벤치마크](#5-적용-완료-beforeafter-벤치마크)

---

## 1. 현재 최적화 현황 (강점)

프로젝트 전반에 걸쳐 높은 수준의 Execution Plan 최적화가 이미 적용되어 있다.

### 1-1. 인덱스 전략

| 기법 | 적용 위치 | 효과 |
|---|---|---|
| Partial Index (6개) | `idx_product_deals`, `idx_order_items_status_return_requested`, `idx_user_coupon_order`, `idx_image_thumbnail`, `idx_outbox_pending`, `idx_outbox_dead_letter` | 희소 조건만 인덱싱하여 인덱스 크기 대폭 절감 |
| Covering Index | `idx_order_items_covering(product_id, created_at) INCLUDE (quantity, subtotal)` | Index-Only Scan으로 테이블 heap 접근 제거 |
| Composite Index 선행 컬럼 최적화 | `idx_product_category(category_id, is_active, sales_count DESC)` 등 11개 | WHERE → ORDER BY 순서에 맞춘 컬럼 배치로 Range Scan + Ordered Retrieval |
| GIN FTS Index | `idx_product_name_gin`, `idx_review_content_gin` | `to_tsvector` + `plainto_tsquery` 검색에서 Full Table Scan 회피 |
| Expression Index | `uk_users_username_lower(LOWER(username))` | 대소문자 비구분 조회에서 인덱스 활용 |

### 1-2. 쿼리 패턴

| 기법 | 적용 위치 | 효과 |
|---|---|---|
| CQRS 읽기 모델 (DB 뷰 4개) | `v_product_list`, `v_order_list`, `v_review_list`, `v_wishlist_list` | JPA 프록시/스냅샷 제거, 필요 컬럼만 SELECT, 썸네일 서브쿼리 인라인 |
| 2-쿼리 패턴 | `OrderRepository.findWithItemsByOrderIds()` | Page + 컬렉션 JOIN FETCH 시 HHH000104 (인메모리 페이징) 방지 |
| Keyset Pagination | `UserRepository.findUsersAfterIdWithTier()` | OFFSET 성능 절벽 O(offset+limit) → O(limit) |
| 벌크 비관적 잠금 | `ProductRepository.findAllByIdInWithLock()` | N개 개별 SELECT FOR UPDATE → IN 절 1회, productId 정렬로 데드락 방지 |
| CAS 원자적 UPDATE | `ProductRepository.decreaseStockAtomic()` | 재고 차감 + 판매량 증가 + 버전 증가를 단일 UPDATE로 처리 |
| NOT EXISTS Anti-Join | `OrderItemRepository.findDeliveredItemsForReviewExcludingReviewed()` | LEFT JOIN + IS NULL 대비 대규모 데이터에서 더 안정적인 실행 계획 |
| CROSS JOIN 집계 | `CouponRepository.getCouponStatsRaw()` | 4개 COUNT 쿼리를 단일 네트워크 라운드트립으로 통합 |
| 배치 삭제 (LIMIT) | `SearchLogRepository.deleteBatchOlderThan()` | WAL 급증·Row Lock 장시간 유지 방지 |

### 1-3. 설정

| 항목 | 설정값 | 효과 |
|---|---|---|
| `open-in-view` | `false` | 트랜잭션 밖 Lazy Loading 원천 차단, 명시적 fetch 전략 강제 |
| `default_batch_fetch_size` | 100 | Lazy 컬렉션 초기화 시 IN 절 일괄 로딩 |
| `jdbc.batch_size` | 100 | INSERT/UPDATE 배치 처리 |
| `order_inserts`, `order_updates` | true | 배치 효율 극대화를 위한 SQL 재정렬 |
| `connection-init-sql` | `SET lock_timeout = '5s'` | 비관적 잠금 무한 대기 방지, 커넥션 풀 고갈 예방 |
| HikariCP `maximum-pool-size` | 17 (8코어 × 2 + 1) | PostgreSQL 권장 공식에 맞춘 풀 크기 |

---

## 2. 추가 최적화 가능 항목

### 2-1. `findPopularKeywords()` — 인덱스 미활용 GROUP BY

**심각도**: HIGH
**파일**: `SearchLogRepository.java:13`

```sql
SELECT search_keyword, COUNT(*) as cnt
FROM search_logs
WHERE searched_at > NOW() - INTERVAL '7 days'
GROUP BY search_keyword
ORDER BY cnt DESC
LIMIT 10
```

**현재 실행 계획 (추정)**:
```
Seq Scan on search_logs (filter: searched_at > ...)
  → HashAggregate (group: search_keyword)
    → Sort (key: cnt DESC)
      → Limit 10
```

**문제**: 현재 인덱스 구성에서 이 쿼리에 활용 가능한 인덱스가 제한적이다.

- `idx_search_keyword(search_keyword, searched_at DESC)` — 선행 컬럼이 `search_keyword`이므로 `WHERE searched_at > ...` 범위 조건의 range scan에 활용 불가.
- `idx_search_date(searched_at DESC)` — 날짜 범위 필터링에는 활용 가능하나, 필터 후 전체 결과에 대해 HashAggregate + Sort 발생.

50M 테이블에서 7일치 데이터가 수십만~수백만 건이면 HashAggregate + Sort 비용이 크다.

**완화 요소**: `@Cacheable("popularKeywords", sync=true)`로 보호되어 실행 빈도는 낮다. 캐시 미스 시에만 실행된다.

**개선 방안**: 선행 컬럼을 `searched_at`으로 둔 복합 인덱스를 추가하면 날짜 범위의 range scan 효율이 개선된다.

```sql
CREATE INDEX idx_search_date_keyword ON search_logs(searched_at DESC, search_keyword);
```

이 인덱스로 `searched_at > NOW() - 7 days` 범위를 Index Range Scan한 뒤 `search_keyword`를 인덱스에서 직접 읽어 GROUP BY에 활용할 수 있다 (Index-Only Scan 가능성).
단, 7일치 데이터 볼륨 자체가 크면 HashAggregate 비용은 여전하므로, `SearchLogCleanupScheduler`의 정리 주기(30일)를 단축하여 테이블 전체 크기를 줄이는 것이 근본 해결이다.

---

### 2-2. `searchByKeywordLikeFlat()` — 양방향 LIKE Full Scan

**심각도**: HIGH
**파일**: `ProductRepository.java:194-200`

```sql
WHERE v.is_active = true
  AND LOWER(v.product_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
```

**현재 실행 계획 (추정)**:
```
Seq Scan on products (filter: is_active = true AND lower(product_name) LIKE '%keyword%')
  → 1M rows full scan
```

**문제**: `%keyword%` 패턴은 B-tree 인덱스를 사용할 수 없어 products 테이블(1M건)을 Full Seq Scan한다. `LOWER()` 함수 적용으로 expression index도 매칭되지 않는다.

**실행 경로**: FTS 폴백 전용이다. `ProductQueryService.java:142-150`에서 (1) FTS 쿼리 예외 발생 시, (2) FTS 결과가 비어 있을 때 실행된다. 정상 경로의 `searchByKeywordFlat()`은 GIN tsvector 인덱스를 활용하므로 문제없다.

**개선 방안**: PostgreSQL `pg_trgm` extension의 GIN trigram 인덱스를 추가하면 양방향 LIKE에서도 인덱스를 활용할 수 있다.

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_product_name_trgm ON products USING gin(LOWER(product_name) gin_trgm_ops);
```

trigram 인덱스는 `LIKE '%keyword%'` 패턴에서 GIN Index Scan을 활용하여 Seq Scan을 회피한다. `LOWER()` 함수 적용 시에는 위와 같이 expression index로 생성해야 한다.

**대안**: `pg_trgm` 도입이 어려우면 LIKE 폴백 자체를 제거하고, FTS 실패 시 빈 결과를 반환하는 것도 선택지이다. 다만 한글 단독 자모 검색 등 FTS가 커버하지 못하는 케이스가 있어 폴백의 존재 자체에는 의미가 있다.

---

### 2-3. `findYearlySpentByUser()` — 대량 GROUP BY 인덱스 부재

**심각도**: MEDIUM
**파일**: `OrderRepository.java:63-68`

```sql
SELECT o.userId, COALESCE(SUM(o.finalAmount), 0) FROM Order o
WHERE o.orderStatus <> CANCELLED
  AND o.orderDate >= :startDate AND o.orderDate < :endDate
GROUP BY o.userId
```

**현재 실행 계획 (추정)**:
```
Index Scan on idx_order_date (range: startDate..endDate)
  → Filter (orderStatus <> 'CANCELLED')
    → HashAggregate (group: userId, agg: SUM(finalAmount))
```

**문제**: orders 테이블(20M건)에서 1년치 데이터(대부분의 행)를 집계한다. 현재 인덱스:

- `idx_order_user(user_id, order_date DESC)` — 선행 컬럼이 `user_id`이므로 `order_date` 범위 조건 단독으로 활용 불가.
- `idx_order_date(order_date DESC)` — 날짜 range scan 가능하나 `orderStatus` 필터 + `userId` GROUP BY에서 추가 비용 발생.

1년치가 테이블의 대부분이면 플래너가 Seq Scan을 선택할 가능성도 있다.

**완화 요소**: `TierScheduler`에서 연 1회(매년 1월 1일) 배치로 실행되며, 실시간 사용자 요청 경로가 아니다.

**개선 방안**: 취소 주문을 제외한 partial covering index를 추가하면 Index-Only Scan이 가능하다.

```sql
CREATE INDEX idx_order_yearly_spent_non_cancelled
    ON orders(order_date)
    INCLUDE (user_id, final_amount)
    WHERE order_status <> 'CANCELLED';
```

이 인덱스로 `order_date` range scan → `user_id`, `final_amount`를 인덱스에서 직접 읽어 heap 접근 없이 집계할 수 있다.
`order_status <> 'CANCELLED'`는 partial predicate로 인덱스 자체에 흡수되므로, 기존 `idx_order_date` 대비 인덱스 크기를 줄이면서 필터 비용도 제거한다.

**2026-04-02 적용 결과**: `idx_order_yearly_spent_non_cancelled`를 추가했고, 600K orders 벤치마크에서
`Bitmap Heap Scan → Index Only Scan`, `108.014 ms → 40.589 ms`로 개선됐다. 상세 수치는 §5-3 참고.

---

### 2-4. `v_order_list` 뷰 — 상관 서브쿼리 2개

**심각도**: MEDIUM
**파일**: `schema.sql:773-792`

```sql
(SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count,
(SELECT oi2.product_name FROM order_items oi2
 WHERE oi2.order_id = o.order_id
 ORDER BY oi2.order_item_id LIMIT 1) AS first_product_name
```

**현재 실행 계획 (추정)**:
```
각 주문 행마다:
  → Index Scan on idx_order_items_order(order_id) → COUNT(*)
  → Index Scan on idx_order_items_order(order_id) → Sort(order_item_id) → LIMIT 1
```

**문제**: 주문 행마다 2개의 상관 서브쿼리가 실행된다. `idx_order_items_order(order_id)` 인덱스로 각 서브쿼리는 Index Scan이지만, 관리자 전체 주문 목록(`findAllOrdersFlat`)에서 페이지당 20건이면 40번의 서브쿼리가 실행된다.

**완화 요소**:
- 페이징이 적용되어 서브쿼리 실행 횟수가 `pageSize × 2`로 제한된다.
- `countQuery`가 뷰가 아닌 `orders` 테이블을 직접 조회하도록 분리되어 count에서는 서브쿼리가 실행되지 않는다.
- `idx_order_items_order(order_id)` 인덱스로 각 서브쿼리의 개별 비용은 매우 작다.

**개선 방안**: 현재 구조로 충분하다. 만약 페이지 크기가 커지거나 응답 지연이 관측되면, `orders` 테이블에 `item_count` 컬럼을 비정규화하여 서브쿼리를 제거하는 방안을 고려할 수 있다. 단, 비정규화는 `order_items` INSERT/DELETE 시 동기화 부담이 추가되므로 트레이드오프를 평가해야 한다.

---

### 2-5. `countByStatus(OrderItemStatus)` — Partial Index 매칭 불확실

**심각도**: LOW
**파일**: `OrderItemRepository.java:98`

```java
long countByStatus(OrderItemStatus status);
```

**스키마**: `schema.sql:618-630`
```sql
CREATE INDEX idx_order_items_status_return_requested
    ON order_items (status)
    WHERE status = 'RETURN_REQUESTED';
```

**문제**: Spring Data JPA가 생성하는 쿼리는 `SELECT COUNT(*) FROM order_items WHERE status = ?`이며, 파라미터가 바인딩된다. PostgreSQL 플래너는 Partial Index의 WHERE 조건(`status = 'RETURN_REQUESTED'`)과 쿼리의 바인딩 파라미터를 매칭해야 하는데, Prepared Statement의 generic plan에서는 파라미터 값을 모르므로 Partial Index를 선택하지 않을 수 있다.

**확인 방법**: 실제 `EXPLAIN ANALYZE`로 Partial Index 활용 여부를 확인해야 한다.

```sql
EXPLAIN ANALYZE SELECT COUNT(*) FROM order_items WHERE status = 'RETURN_REQUESTED';
```

**개선 방안**: Partial Index가 generic plan에서 활용되지 않는다면, `@Query`로 네이티브 쿼리를 작성하여 리터럴 값을 직접 전달한다.

```java
@Query(value = "SELECT COUNT(*) FROM order_items WHERE status = 'RETURN_REQUESTED'",
       nativeQuery = true)
long countReturnRequested();
```

**2026-04-02 적용 결과**: `OrderQueryService.getPendingReturnCount()`를
`countByStatus(RETURN_REQUESTED)`에서 `countReturnRequested()`로 전환했다.
1.8M order_items 벤치마크에서 `Parallel Seq Scan → Index Only Scan`,
`83.255 ms → 1.921 ms`로 개선됐다. 상세 수치는 §5-4 참고.

---

### 2-6. `syncAllHelpfulCounts()` — 전체 테이블 GROUP BY

**심각도**: LOW
**파일**: `ReviewRepository.java:80-89`

```sql
UPDATE reviews r SET helpful_count = sub.actual_count
FROM (
    SELECT review_id, COUNT(*) as actual_count
    FROM review_helpfuls
    GROUP BY review_id
) sub
WHERE r.review_id = sub.review_id AND r.helpful_count != sub.actual_count
```

**현재 실행 계획 (추정)**:
```
SubPlan:
  Seq Scan on review_helpfuls
    → HashAggregate (group: review_id, agg: COUNT(*))
MainPlan:
  Hash Join (r.review_id = sub.review_id AND r.helpful_count != sub.actual_count)
    → UPDATE reviews
```

**문제**: `review_helpfuls` 전체에 대해 GROUP BY를 수행한다. 데이터가 수백만 건이면 HashAggregate의 메모리 사용량이 `work_mem`을 초과하여 디스크 spill이 발생할 수 있다.

**완화 요소**:
- `ReviewHelpfulSyncScheduler`에서 야간 배치로 실행되어 실시간 영향 없음.
- `uk_review_helpful_user(review_id, user_id)` unique index가 있어 `review_id`에 대한 Index Scan 자체는 가능.
- `r.helpful_count != sub.actual_count` 필터로 실제 UPDATE 대상 행은 불일치 건만으로 제한.

**개선 방안**: 현행 유지 가능하다. 만약 `review_helpfuls`가 수천만 건으로 증가하여 배치 실행 시간이 수분 이상 걸리면, 최근 변경된 리뷰만 대상으로 하는 증분 동기화를 고려할 수 있다.

```sql
-- 예: 최근 24시간 내 변경된 review_id만 대상으로 동기화
UPDATE reviews r SET helpful_count = sub.actual_count
FROM (
    SELECT review_id, COUNT(*) as actual_count
    FROM review_helpfuls
    WHERE review_id IN (
        SELECT DISTINCT review_id FROM review_helpfuls
        WHERE created_at > NOW() - INTERVAL '24 hours'
    )
    GROUP BY review_id
) sub
WHERE r.review_id = sub.review_id AND r.helpful_count != sub.actual_count
```

단, 이 경우 오래된 불일치는 보정되지 않으므로 주간/월간 전체 동기화와 병행해야 한다.

---

## 3. 우선순위 매트릭스

| # | 대상 쿼리 | 파일 위치 | 심각도 | 영향 빈도 | 실행 경로 | 조치 난이도 |
|---|---|---|---|---|---|---|
| 1 | `findPopularKeywords()` GROUP BY | `SearchLogRepository.java:13` | HIGH | 캐시 미스 시 | 사용자 검색 페이지 | 인덱스 1개 추가 |
| 2 | `searchByKeywordLikeFlat()` LIKE `%keyword%` | `ProductRepository.java:194` | HIGH | FTS 폴백 시 | 사용자 검색 결과 | pg_trgm + GIN 인덱스 |
| 3 | `findYearlySpentByUser()` 대량 GROUP BY | `OrderRepository.java:63` | MEDIUM | 연 1회 배치 | TierScheduler | partial covering index 추가 |
| 4 | `v_order_list` 상관 서브쿼리 | `schema.sql:788-791` | MEDIUM | 관리자 조회 시 | 관리자 주문 목록 | 현행 유지 가능 |
| 5 | `countByStatus()` Partial Index 매칭 | `OrderItemRepository.java:98` | LOW | 대시보드 조회 시 | 관리자 대시보드 | 리터럴 네이티브 쿼리 분리 |
| 6 | `syncAllHelpfulCounts()` 전체 GROUP BY | `ReviewRepository.java:80` | LOW | 야간 배치 | ReviewHelpfulSyncScheduler | 현행 유지 가능 |

### 조치 권장

- **적용 완료 (항목 1, 2, 3, 5)**: 인덱스/쿼리 경로 보강으로 해결. 코드 변경은 §4, 벤치마크 결과는 §5 참고.
- **모니터링 후 판단 (항목 4)**: 관리자 조회 경로이므로 실제 실행 시간을 측정한 뒤 필요 시 적용한다.
- **현행 유지 (항목 6)**: 영향이 작고 배치 경로라 현행 유지 가능하다.

---

## 4. 적용 완료: 코드 변경사항

항목 1, 2, 3은 `schema.sql`/migration에 인덱스를 추가하여 해결했고,
항목 5는 Repository/Service 경로를 리터럴 네이티브 쿼리로 분리하여 해결하였다.

### 변경 파일

| 파일 | 변경 내용 |
|---|---|
| `src/main/resources/schema.sql` | 인덱스 3개 + extension 1개 반영 |
| `src/main/resources/migration/V19__optimize_yearly_spent_and_return_count.sql` | 연간 실적 집계용 partial covering index 추가 |
| `src/main/java/com/shop/domain/order/repository/OrderItemRepository.java` | `countReturnRequested()` 네이티브 쿼리 추가 |
| `src/main/java/com/shop/domain/order/service/OrderQueryService.java` | 관리자 대시보드 반품 건수 경로를 리터럴 쿼리로 전환 |
| `src/test/java/com/shop/domain/order/service/OrderQueryServiceReturnTest.java` | 새 카운트 경로에 맞춰 테스트 갱신 |
| `load-test/setup-explain-benchmark.sql` 외 3개 | EXPLAIN ANALYZE 재현 데이터/측정 스크립트 추가 |

### 4-1. 항목 1: `idx_search_date_keyword` 추가

**위치**: `schema.sql` — Search_Logs 인덱스 블록 뒤

```diff
  CREATE INDEX idx_search_user ON search_logs(user_id, searched_at DESC);
  CREATE INDEX idx_search_date ON search_logs(searched_at DESC);

+ -- findPopularKeywords() GROUP BY 최적화: Index-Only Scan용 복합 인덱스.
+ --
+ -- 문제: 기존 idx_search_date(searched_at DESC)는 날짜 범위 필터링에는 활용되지만,
+ -- 필터 후 search_keyword에 대한 GROUP BY에서 Heap 접근이 필요하다.
+ -- 7일치 데이터가 수만~수십만 건이면 Bitmap Heap Scan + HashAggregate 비용이 크다.
+ --
+ -- 해결: (searched_at DESC, search_keyword) 복합 인덱스로 날짜 Range Scan 후
+ -- search_keyword를 인덱스에서 직접 읽어 Heap 접근 없이 Index-Only Scan을 달성한다.
+ -- Bitmap Heap Scan 대비 실행 시간 ~59% 감소, 버퍼 접근 ~87% 감소 확인됨.
+ CREATE INDEX idx_search_date_keyword ON search_logs(searched_at DESC, search_keyword);
+
  -- Point_History 인덱스
```

**설계 근거**:
- 선행 컬럼 `searched_at DESC`: `WHERE searched_at > NOW() - 7 days` 범위 조건의 Range Scan에 활용.
- 후행 컬럼 `search_keyword`: GROUP BY 대상 컬럼을 인덱스에 포함시켜 Index-Only Scan 달성. 테이블 heap 접근 0.
- 기존 `idx_search_date(searched_at DESC)`는 제거하지 않는다. 다른 쿼리(`deleteBatchOlderThan` 등)에서 여전히 활용되며, 새 복합 인덱스와 역할이 다르다.

### 4-2. 항목 2: `pg_trgm` + `idx_product_name_trgm` 추가

**위치**: `schema.sql` — Products 인덱스 블록, `idx_product_name_gin` 직후

```diff
  CREATE INDEX idx_product_name_gin ON products USING gin(to_tsvector('simple', product_name));

+ -- searchByKeywordLikeFlat() LIKE '%keyword%' 최적화: pg_trgm GIN 인덱스.
+ --
+ -- 문제: FTS 폴백 시 LOWER(product_name) LIKE '%keyword%' 쿼리가 실행되는데,
+ -- 양방향 LIKE(%...%)는 B-tree 인덱스를 활용할 수 없어 Full Seq Scan이 발생한다.
+ --
+ -- 해결: pg_trgm의 gin_trgm_ops로 trigram 기반 GIN 인덱스를 생성한다.
+ -- LOWER() expression index로 대소문자 비구분 LIKE 조건에서 Bitmap Index Scan을 활용한다.
+ -- Seq Scan 대비 실행 시간 ~77% 감소, 버퍼 접근 ~48% 감소 확인됨.
+ CREATE EXTENSION IF NOT EXISTS pg_trgm;
+ CREATE INDEX idx_product_name_trgm ON products USING gin(LOWER(product_name) gin_trgm_ops);
+
  CREATE INDEX idx_product_category ON products(category_id, is_active, sales_count DESC);
```

**설계 근거**:
- `pg_trgm` extension: 문자열을 3-gram(trigram)으로 분해하여 GIN 인덱스에 저장. `%keyword%` 패턴에서 trigram 매칭으로 후보 행을 선별.
- `LOWER(product_name)` expression index: 쿼리가 `LOWER(product_name) LIKE LOWER(...)` 패턴이므로 expression이 일치해야 인덱스가 활용된다.
- `gin_trgm_ops` operator class: trigram 기반 유사도 연산과 LIKE/ILIKE 패턴 매칭을 지원.
- 기존 `idx_product_name_gin`(tsvector FTS)과 역할이 다르다. FTS는 단어 단위 매칭, trigram은 부분 문자열 매칭.

### 4-3. 항목 3: `idx_order_yearly_spent_non_cancelled` 추가

**위치**: `schema.sql` — Orders 인덱스 블록

```diff
  CREATE INDEX idx_order_user ON orders(user_id, order_date DESC);
  CREATE INDEX idx_order_status ON orders(order_status, order_date);
  CREATE INDEX idx_order_date ON orders(order_date DESC);

+ -- TierScheduler 전년도 실적 집계 최적화:
+ -- 취소 주문을 제외한 연간 범위 스캔에서 user_id, final_amount를 heap 접근 없이 읽는다.
+ CREATE INDEX idx_order_yearly_spent_non_cancelled
+     ON orders(order_date)
+     INCLUDE (user_id, final_amount)
+     WHERE order_status <> 'CANCELLED';
```

**설계 근거**:
- 쿼리 predicate가 항상 `order_status <> 'CANCELLED'`이므로 partial index로 인덱스 크기를 줄일 수 있다.
- 선행 컬럼 `order_date`는 연간 범위 조건의 range scan에 사용된다.
- `INCLUDE (user_id, final_amount)`로 집계에 필요한 컬럼을 heap 접근 없이 읽어 Index-Only Scan을 유도한다.
- 연 1회 배치 경로이지만, 사용자 수가 많아질수록 heap 접근 제거 효과가 누적된다.

### 4-4. 항목 5: `countReturnRequested()` 네이티브 쿼리 분리

**위치**: `OrderItemRepository.java`, `OrderQueryService.java`

```diff
- long countByStatus(OrderItemStatus status);
+ long countByStatus(OrderItemStatus status);
+
+ @Query(value = "SELECT COUNT(*) FROM order_items WHERE status = 'RETURN_REQUESTED'",
+        nativeQuery = true)
+ long countReturnRequested();
```

```diff
- return orderItemRepository.countByStatus(OrderItemStatus.RETURN_REQUESTED);
+ return orderItemRepository.countReturnRequested();
```

**설계 근거**:
- partial index `idx_order_items_status_return_requested`는 리터럴 조건 `status = 'RETURN_REQUESTED'`에 정확히 대응한다.
- 파라미터 바인딩 쿼리는 generic plan에서 Partial Index 매칭이 불확실할 수 있다.
- 관리자 대시보드 집계는 고정된 상태값 하나만 필요하므로, 범용 메서드보다 고정 리터럴 경로가 더 명확하고 빠르다.

### 인덱스 총 개수 변경

```diff
- -- 총 19개 테이블, 57개 인덱스 생성됨 (일반 54 + UNIQUE 3)
+ -- 총 19개 테이블, 60개 인덱스 생성됨 (일반 57 + UNIQUE 3)
```

### 영향받지 않는 코드

항목 1, 2, 3은 인덱스 추가만으로 플래너가 최적 실행 계획을 선택한다.
항목 5는 관리자 대시보드의 고정 집계 경로만 별도 메서드로 분리했다.

| 파일 | 쿼리 메서드 | 변경 여부 |
|---|---|---|
| `SearchLogRepository.java:13` | `findPopularKeywords()` | 변경 없음 |
| `ProductRepository.java:41` | `searchByKeywordLike()` | 변경 없음 |
| `ProductRepository.java:194` | `searchByKeywordLikeFlat()` | 변경 없음 |
| `OrderRepository.java:63-68` | `findYearlySpentByUser()` | 변경 없음 |
| `OrderItemRepository.java:98` | `countByStatus()` | 유지 |
| `OrderItemRepository.java:110` | `countReturnRequested()` | 신규 |
| `ProductQueryService.java:142-150` | `search()` — FTS/LIKE 폴백 로직 | 변경 없음 |
| `SearchService.java:64-68` | `getPopularKeywords()` — 캐시 래핑 | 변경 없음 |
| `OrderQueryService.java:177` | `getPendingReturnCount()` | 새 리터럴 쿼리 경로 사용 |

---

## 5. 적용 완료: Before/After 벤치마크

### 벤치마크 환경

- 항목 1, 2: PostgreSQL 14, search_logs 100,000건, products 5,056건
- 항목 3, 5: PostgreSQL 16, orders 600,000건, order_items 1,800,000건, `RETURN_REQUESTED` 25,969건
- 측정 스크립트: `load-test/setup-explain-benchmark.sql`, `load-test/explain-benchmark-before.sql`,
  `load-test/explain-benchmark-after.sql`, `load-test/run-explain-benchmark.sh`
- 공통: VACUUM ANALYZE 후 측정 (visibility map 갱신 상태)

### 5-1. 항목 1: `findPopularKeywords()` — `idx_search_date_keyword` 추가

#### Before

```
Bitmap Heap Scan on search_logs  (cost=446.80..1572.80)  (actual time=2.585..8.785 rows=21617)
  Recheck Cond: (searched_at > (now() - '7 days'))
  Heap Blocks: exact=741
  Buffers: shared hit=807
  →  Bitmap Index Scan on idx_search_date  (actual time=2.499..2.499 rows=21617)
        Index Cond: (searched_at > (now() - '7 days'))
        Buffers: shared hit=66
→  HashAggregate (group: search_keyword)
  →  Sort (key: count(*) DESC)
    →  Limit 10

Execution Time: 12.408 ms
Buffers: shared hit=810
```

**병목**: `idx_search_date`로 날짜 범위를 필터링한 뒤 **Bitmap Heap Scan**으로 테이블 heap에 접근하여 `search_keyword`를 읽었다. heap 접근 741 블록이 I/O 비용의 대부분을 차지.

#### After

```
Index Only Scan using idx_search_date_keyword on search_logs  (cost=0.42..805.42)
                                                               (actual time=0.016..2.318 rows=21614)
  Index Cond: (searched_at > (now() - '7 days'))
  Heap Fetches: 0
  Buffers: shared hit=105
→  HashAggregate (group: search_keyword)
  →  Sort (key: count(*) DESC)
    →  Limit 10

Execution Time: 5.125 ms
Buffers: shared hit=105
```

**개선**: `(searched_at DESC, search_keyword)` 복합 인덱스로 **Index-Only Scan**을 달성. 테이블 heap 접근 0회 (`Heap Fetches: 0`).

#### 성능 비교

| 지표 | Before | After | 변화 |
|---|---|---|---|
| Scan 방식 | Bitmap Heap Scan | Index-Only Scan | Heap 접근 제거 |
| Execution Time | 12.408 ms | 5.125 ms | **-59%** |
| Buffers (shared hit) | 810 | 105 | **-87%** |
| Heap Fetches | 741 blocks | 0 | **-100%** |
| 쿼리 변경 | - | 불필요 | 인덱스만 추가 |

---

### 5-2. 항목 2: `searchByKeywordLikeFlat()` — `pg_trgm` GIN 인덱스 추가

#### Before

```
Seq Scan on products  (cost=0.00..275.12)  (actual time=0.468..4.891 rows=800)
  Filter: (is_active AND (lower(product_name) ~~ lower(concat('%', '노트북', '%'))))
  Rows Removed by Filter: 4256
  Buffers: shared hit=174

Execution Time: 4.935 ms
```

**병목**: B-tree 인덱스는 양방향 LIKE(`%keyword%`)를 지원하지 않아 5,056건 전체를 **Seq Scan**. `is_active` 필터와 `LOWER(LIKE)` 필터를 행마다 평가하여 4,256건을 제거.

#### After

```
Bitmap Heap Scan on products  (cost=17.10..207.44)  (actual time=0.084..1.032 rows=800)
  Recheck Cond: (lower(product_name) ~~ lower(concat('%', '노트북', '%')))
  Filter: is_active
  Heap Blocks: exact=87
  Buffers: shared hit=90
  →  Bitmap Index Scan on idx_product_name_trgm  (actual time=0.062..0.062 rows=800)
        Index Cond: (lower(product_name) ~~ lower(concat('%', '노트북', '%')))
        Buffers: shared hit=3

Execution Time: 1.118 ms
```

**개선**: `pg_trgm` GIN 인덱스로 `LIKE '%노트북%'`을 **Bitmap Index Scan**으로 처리. trigram 매칭으로 후보 행만 선별한 뒤 heap에서 `is_active` 필터만 적용.

#### 성능 비교

| 지표 | Before | After | 변화 |
|---|---|---|---|
| Scan 방식 | Seq Scan (Full Table) | Bitmap Index Scan + Heap | 인덱스 활용 |
| Execution Time | 4.935 ms | 1.118 ms | **-77%** |
| Buffers (shared hit) | 174 | 90 | **-48%** |
| Rows Removed by Filter | 4,256 | 0 (Recheck만) | **필터링 비용 제거** |
| 쿼리 변경 | - | 불필요 | 인덱스만 추가 |

---

### 5-3. 항목 3: `findYearlySpentByUser()` — partial covering index 추가

#### Before

```
HashAggregate  (actual time=87.774..106.118 rows=27000)
  Group Key: user_id
  Buffers: shared hit=3 read=4247 written=4247
  ->  Bitmap Heap Scan on orders  (actual time=7.244..50.554 rows=107420)
        Recheck Cond: (order_date >= '2025-01-01' AND order_date < '2026-01-01')
        Filter: (order_status <> 'CANCELLED')
        Rows Removed by Filter: 11972
        Heap Blocks: exact=3809
        ->  Bitmap Index Scan on idx_order_date  (actual time=6.927..6.928 rows=119392)

Execution Time: 108.014 ms
```

**병목**: 날짜 범위를 `idx_order_date`로 찾은 뒤 `order_status`, `user_id`, `final_amount`를 읽기 위해 heap에 3,809블록 접근했다.

#### After

```
HashAggregate  (actual time=30.578..39.114 rows=27000)
  Group Key: user_id
  Buffers: shared hit=1 read=533 written=105
  ->  Index Only Scan using idx_order_yearly_spent_non_cancelled on orders
        (actual time=0.030..9.850 rows=107420)
        Index Cond: (order_date >= '2025-01-01' AND order_date < '2026-01-01')
        Heap Fetches: 0

Execution Time: 40.589 ms
```

**개선**: partial covering index로 `Bitmap Heap Scan → Index Only Scan` 전환. 취소 주문 필터와 heap 접근이 모두 제거됐다.

#### 성능 비교

| 지표 | Before | After | 변화 |
|---|---|---|---|
| Scan 방식 | Bitmap Heap Scan | Index-Only Scan | Heap 접근 제거 |
| Execution Time | 108.014 ms | 40.589 ms | **-62%** |
| Buffers (shared hit + read) | 4,250 | 534 | **-87%** |
| Heap 접근 | Heap Blocks 3,809 | Heap Fetches 0 | **-100%** |
| 쿼리 변경 | - | 불필요 | 인덱스만 추가 |

---

### 5-4. 항목 5: `countReturnRequested()` — 리터럴 네이티브 쿼리 분리

#### Before

```
Finalize Aggregate  (actual time=80.309..83.227 rows=1)
  ->  Gather
        ->  Partial Aggregate
              ->  Parallel Seq Scan on order_items
                    Filter: (status = $1)
                    Rows Removed by Filter: 591344

Execution Time: 83.255 ms
```

**병목**: generic prepared plan이 partial index를 선택하지 못해 1.8M건 전체를 `Parallel Seq Scan`으로 스캔했다.

#### After

```
Aggregate  (actual time=1.910..1.910 rows=1)
  ->  Index Only Scan using idx_order_items_status_return_requested on order_items
        (actual time=0.015..1.126 rows=25969)
        Heap Fetches: 0

Execution Time: 1.921 ms
```

**개선**: 리터럴 네이티브 쿼리로 partial index를 정확히 매칭해 `Parallel Seq Scan → Index Only Scan` 전환.

#### 성능 비교

| 지표 | Before | After | 변화 |
|---|---|---|---|
| Scan 방식 | Parallel Seq Scan | Index-Only Scan | partial index 활용 |
| Execution Time | 83.255 ms | 1.921 ms | **-97.7%** |
| Buffers (shared hit + read) | 31,208 | 23 | **-99.9%** |
| Heap Fetches | 전체 행 스캔 | 0 | **Heap 접근 제거** |
| 쿼리 변경 | 파라미터 바인딩 | 리터럴 네이티브 | 관리자 대시보드 경로만 분리 |

### 스케일 예측

현재 벤치마크는 소규모 데이터(search_logs 100K, products 5K)에서 수행되었다.
운영 규모(search_logs 50M, products 1M)에서는 차이가 더 극적이다:

| 지표 | 항목 1 (100K→50M) | 항목 2 (5K→1M) |
|---|---|---|
| Seq/Bitmap Heap Scan 비용 | heap 접근 블록 수가 500배 증가 → 수 초 | 전체 테이블 스캔 시간이 200배 증가 → 수 초 |
| Index-Only/Bitmap Index Scan | 인덱스 크기만 비례 증가, heap 접근 0 | trigram 매칭으로 후보만 접근, 대부분 스킵 |
| 예상 개선 폭 | **수 초 → 수십~수백 ms** | **수 초 → 수십 ms** |

# Execution Plan 최적화 검토

> **범위**: 전체 Repository 쿼리·스키마 인덱스·뷰 서브쿼리를 대상으로 Execution Plan 관점에서 최적화 가능 지점을 식별한다.
> 기존에 잘 최적화된 부분(강점)과 추가 개선 가능 항목(6건)을 함께 정리하여 운영·유지보수 시 판단 근거를 제공한다.

---

## 목차

1. [현재 최적화 현황 (강점)](#1-현재-최적화-현황-강점)
2. [추가 최적화 가능 항목](#2-추가-최적화-가능-항목)
3. [우선순위 매트릭스](#3-우선순위-매트릭스)

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

**개선 방안**: 커버링 인덱스를 추가하면 Index-Only Scan이 가능하다.

```sql
CREATE INDEX idx_order_yearly_agg
    ON orders(order_date, order_status)
    INCLUDE (user_id, final_amount);
```

이 인덱스로 `order_date` range scan → `order_status` 필터 → `user_id`, `final_amount`를 인덱스에서 직접 읽어 heap 접근 없이 집계할 수 있다.
다만 1년치가 테이블의 대부분이면 플래너가 Index-Only Scan 대신 Seq Scan을 선택할 수 있으므로, 효과는 데이터 분포에 따라 다르다. 연 1회 배치이므로 우선순위는 낮다.

---

### 2-4. `v_order_list` 뷰 — 상관 서브쿼리 2개

**심각도**: MEDIUM
**파일**: `schema.sql:744-763`

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

**스키마**: `schema.sql:612-614`
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

**개선 방안**: Partial Index가 활용되지 않는다면, `@Query`로 네이티브 쿼리를 작성하여 리터럴 값을 직접 전달한다.

```java
@Query(value = "SELECT COUNT(*) FROM order_items WHERE status = 'RETURN_REQUESTED'",
       nativeQuery = true)
long countReturnRequested();
```

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
| 3 | `findYearlySpentByUser()` 대량 GROUP BY | `OrderRepository.java:63` | MEDIUM | 연 1회 배치 | TierScheduler | 커버링 인덱스 추가 |
| 4 | `v_order_list` 상관 서브쿼리 | `schema.sql:759-762` | MEDIUM | 관리자 조회 시 | 관리자 주문 목록 | 현행 유지 가능 |
| 5 | `countByStatus()` Partial Index 매칭 | `OrderItemRepository.java:98` | LOW | 대시보드 조회 시 | 관리자 대시보드 | EXPLAIN 확인 후 판단 |
| 6 | `syncAllHelpfulCounts()` 전체 GROUP BY | `ReviewRepository.java:80` | LOW | 야간 배치 | ReviewHelpfulSyncScheduler | 현행 유지 가능 |

### 조치 권장

- **즉시 조치 (항목 1, 2)**: 사용자 요청 경로에서 Full Scan이 발생할 수 있는 쿼리이다. 인덱스 추가로 해결 가능하며, 기존 쿼리 변경 없이 적용할 수 있다.
- **모니터링 후 판단 (항목 3, 4)**: 배치 또는 관리자 경로이므로 실제 실행 시간을 측정한 뒤 필요 시 적용한다.
- **현행 유지 (항목 5, 6)**: 영향이 작거나 확인 후 판단이 필요하다. EXPLAIN ANALYZE로 검증 후 결정한다.

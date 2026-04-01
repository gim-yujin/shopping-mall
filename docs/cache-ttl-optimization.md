# Caffeine 캐시 TTL 최적화 분석

> **분석일:** 2026-04-01
> **대상:** `CacheConfig`에 정의된 19개 Caffeine 캐시 전수 분석
> **방법:** 코드 정적 분석(데이터 변경 빈도, 무효화 로직, maxSize) + 통합 테스트 정량 측정
> **테스트:** `CacheTtlOptimizationTest` (5개 시나리오)

---

## 1) 현재 캐시 구성 요약

| 캐시 | TTL | PER | maxSize | 무효화 방식 |
|:------|:---:|:---:|--------:|:------------|
| bestSellers | 1분 | O | 200 | `@CacheEvict allEntries` (상품 생성/수정/토글) |
| newArrivals | 1분 | O | 200 | 동일 |
| deals | 1분 | O | 200 | 동일 |
| topCategories | 1분 | O | 100 | TTL만 의존 (무효화 로직 없음) |
| popularKeywords | 1분 | O | 100 | TTL만 의존 (무효화 로직 없음) |
| subCategories | 30분 | O | 500 | 없음 (데이터 불변) |
| categoryDescendants | 30분 | O | 500 | 없음 (데이터 불변) |
| categoryBreadcrumb | 30분 | O | 500 | 없음 (데이터 불변) |
| categoryById | 30분 | O | 500 | 없음 (데이터 불변) |
| productList | 2분 | O | 300 | `@CacheEvict allEntries` |
| searchResults | 2분 | O | 300 | `@CacheEvict allEntries` |
| categoryProducts | 2분 | O | 300 | `@CacheEvict allEntries` |
| productDetail | 2분 | O | 500 | 키 단위 evict + Outbox 비동기 evict |
| productReviews | 30초 | O | 500 | 버전 스탬프 키 (의미적 무효화) |
| productReviewVersion | 60분 | X | 10,000 | 수동 `cache.merge()` |
| activeCoupons | 10초 | O | 200 | `@CacheEvict allEntries` |
| userDetails | 1분 | X | 1,000 | 수동 evict (비밀번호 변경 시) |
| loginAttempts | 15분 | X | 50,000 | 수동 evict (로그인 성공 시) |

---

## 2) 캐시별 정적 분석 결과

### 2-1. 홈 캐시 (bestSellers, newArrivals, deals) — 현재 1분

- **데이터 변경 빈도:** 낮음 (관리자 상품 생성/수정/토글 시에만)
- **무효화 로직:** `ProductService`의 쓰기 메서드에서 `@CacheEvict(allEntries=true)`로 즉시 무효화
- **주문에 의한 재고 변경은 이 캐시를 무효화하지 않음** — `StockChangedEventHandler`는 `productDetail`만 evict
- **판정:** TTL은 CacheEvict 누락 시의 안전망 역할. 관리자 조작 시 즉시 반영되므로 **3분으로 연장 가능**

### 2-2. topCategories — 현재 1분

- **데이터 변경 빈도:** 없음 (CategoryService에 쓰기 메서드 없음, 관리자 API 없음)
- **무효화 로직:** 없음 — TTL 만료에만 의존
- **판정:** 불변 데이터에 1분 TTL은 과도. **10분으로 연장** 시 DB 쿼리 90% 절감

### 2-3. popularKeywords — 현재 1분

- **데이터 변경 빈도:** 연속 (검색 로그 실시간 누적), 그러나 인기 키워드 **순위** 변동은 느림
- **무효화 로직:** 없음 — TTL 만료에만 의존
- **쿼리 비용:** 4.6ms (GROUP BY 집계, 비교적 가벼움)
- **판정:** 순위 변동이 초 단위가 아니므로 **3분으로 연장 가능**

### 2-4. 카테고리 캐시 4종 — 현재 30분

- **데이터 변경 빈도:** 없음 (불변 — 쓰기 메서드/API 부재)
- **무효화 로직:** 없음
- **판정:** 30분 TTL은 보수적이나 **적절**. PER이 불필요한 재계산을 방지

### 2-5. 상품 목록/검색 캐시 3종 — 현재 2분

- **데이터 변경 빈도:** 낮음~중간 (관리자 조작 시)
- **무효화 로직:** `@CacheEvict(allEntries=true)` 완비
- **판정:** **적절** — 변경 불필요

### 2-6. productDetail — 현재 2분

- **데이터 변경 빈도:** 높음 (주문/재고 변동, 리뷰 작성)
- **무효화 로직:** 키 단위 즉시 evict (리뷰) + Outbox 비동기 evict (재고, ~5초 지연)
- **판정:** **적절** — 적극적 무효화와 짧은 TTL의 이중 방어

### 2-7. productReviews — 현재 30초

- **데이터 변경 빈도:** 중~높음 (리뷰 CRUD, 도움돼요)
- **무효화 로직:** `productReviewVersion` 버전 증가 → 캐시 키 자동 변경
- **판정:** **적절** — 버전 스탬프로 즉시 반영, 30초는 안전망

### 2-8. productReviewVersion — 현재 60분

- **설계 의도:** 캐시 키 구성 요소 (메타데이터). 직접 읽기 대상이 아님
- **변경 방식:** `cache.merge()`로 원자적 증가
- **판정:** **의도적 설계** — 60분 TTL은 버전 카운터의 장기 유지를 위한 것

### 2-9. activeCoupons — 현재 10초

- **데이터 변경 빈도:** 낮음 (관리자 쿠폰 생성/수정/토글)
- **무효화 로직:** `@CacheEvict(allEntries=true)` 완비
- **주의:** 쿠폰 `validUntil` 만료는 DB 레벨 → 만료 후 TTL 동안 stale 데이터 노출 가능
- **판정:** 10초는 과도. **30초로 연장** 시 66.7% 절감. 비즈니스적으로 30초 지연 수용 가능

### 2-10. userDetails — 현재 1분

- **데이터 변경 빈도:** 온디맨드 (비밀번호 변경 시에만)
- **무효화 로직:** 비밀번호 변경 시 수동 evict
- **판정:** **적절** — 사용자별 분산, 동시 접근 드묾

### 2-11. loginAttempts — 현재 15분

- **데이터 변경 빈도:** 로그인 시도마다
- **무효화 로직:** 로그인 성공 시 `clearFailures()`, 원자적 `compute()`
- **판정:** **적절** — 보안 정책상 15분 잠금 해제 주기와 일치

---

## 3) 정량 측정 결과

### 3-1. TTL 연장 시 DB 쿼리 절감 시뮬레이션 (1시간 기준)

| 캐시 | 현재 TTL | 추천 TTL | 쿼리 비용 | 현재 갱신 | 추천 후 갱신 | 절감 횟수 | 절감률 |
|:------|:--------:|:--------:|----------:|----------:|-----------:|----------:|-------:|
| bestSellers | 1분 | 3분 | 97.9ms | 60/h | 20/h | 40/h | 66.7% |
| newArrivals | 1분 | 3분 | 97.9ms | 60/h | 20/h | 40/h | 66.7% |
| deals | 1분 | 3분 | 97.9ms | 60/h | 20/h | 40/h | 66.7% |
| topCategories | 1분 | 10분 | 58.9ms | 60/h | 6/h | 54/h | 90.0% |
| popularKeywords | 1분 | 3분 | 4.6ms | 60/h | 20/h | 40/h | 66.7% |
| activeCoupons | 10초 | 30초 | — | 360/h | 120/h | 240/h | 66.7% |

**총 절감: 시간당 454회 불필요한 DB 쿼리 제거**

### 3-2. 캐시 히트율 baseline

| 캐시 | 히트 | 미스 | 히트율 | 비고 |
|:------|-----:|-----:|-------:|:-----|
| topCategories | 9 | 2 | 81.8% | 미스 2회는 PER 조기 갱신 |
| popularKeywords | 50 | 3 | 94.3% | PER 조기 갱신에 의한 미스 포함 |
| bestSellers | 4 | 2 | 66.7% | 동시 10개 요청 중 PER 미스 포함 |
| newArrivals | 3 | 1 | 75.0% | 동시 요청 시 |
| deals | 3 | 1 | 75.0% | 동시 요청 시 |

> PER(확률적 조기 재계산)이 TTL 후반 10~20% 구간에서 조기 갱신을 유발하여 미스 카운트가 증가하지만,
> 이는 하드 만료 시의 thundering herd를 방지하는 정상 동작이다.

### 3-3. maxSize 대비 실제 사용률 (테스트 환경)

| 캐시 | maxSize | 사용 중 | 사용률 |
|:------|--------:|-------:|-------:|
| bestSellers | 200 | 1 | 0.5% |
| newArrivals | 200 | 1 | 0.5% |
| deals | 200 | 1 | 0.5% |
| topCategories | 100 | 1 | 1.0% |
| popularKeywords | 100 | 1 | 1.0% |
| productReviewVersion | 10,000 | 0 | 0.0% |
| loginAttempts | 50,000 | 0 | 0.0% |

> 테스트 환경 특성상 사용률이 매우 낮다. 프로덕션에서는 Prometheus 메트릭
> (`shop.cache.size` vs maxSize)으로 지속 모니터링이 필요하다.
> `loginAttempts`(50,000)과 `productReviewVersion`(10,000)은 프로덕션에서도 과잉 설정 가능성이 있다.

---

## 4) TTL 조정 추천 요약

| 캐시 | 현재 | 추천 | 근거 | 위험도 |
|:------|:----:|:----:|:-----|:------:|
| **topCategories** | 1분 | **10분** | 불변 데이터 + 관리자 API 없음. 90% 쿼리 절감 | 없음 |
| **bestSellers** | 1분 | **3분** | `@CacheEvict`가 즉시 무효화. TTL은 안전망 | 매우 낮음 |
| **newArrivals** | 1분 | **3분** | 동일 | 매우 낮음 |
| **deals** | 1분 | **3분** | 동일 | 매우 낮음 |
| **popularKeywords** | 1분 | **3분** | 순위 변동이 초 단위가 아님 | 낮음 |
| **activeCoupons** | 10초 | **30초** | `@CacheEvict`가 즉시 무효화. 만료 쿠폰 30초 지연 수용 | 낮음 |
| 나머지 13개 | — | **변경 불필요** | TTL과 데이터 변경 빈도가 적절히 매칭 | — |

---

## 5) 변경하지 않는 캐시의 근거

| 캐시 | TTL | 유지 근거 |
|:------|:---:|:----------|
| 카테고리 4종 | 30분 | 데이터 불변이지만 30분 TTL이 이미 보수적으로 안전 |
| productList/searchResults/categoryProducts | 2분 | CacheEvict 완비 + 적절한 TTL |
| productDetail | 2분 | 높은 변경 빈도 + 적극적 무효화(Outbox) + PER 이중 방어 |
| productReviews | 30초 | 버전 스탬프 키로 즉시 반영 |
| productReviewVersion | 60분 | 메타데이터 캐시, 의도적 장기 TTL |
| userDetails | 1분 | 사용자별 분산, 변경 시 즉시 evict |
| loginAttempts | 15분 | 보안 정책(잠금 해제 주기)과 일치 |

---

## 6) 후속 과제

1. **프로덕션 모니터링 대시보드 구성**
   - Grafana 패널: 캐시별 히트율 시계열 (`shopping_mall:cache_hit_ratio:rate1m`)
   - 알림: 히트율 < 80% 시 알림 (`shop_cache_hit_rate < 0.8`)

2. **TTL 변경 후 A/B 비교**
   - 추천 TTL 적용 후 1주일간 히트율/미스율/DB 부하 비교
   - 특히 `activeCoupons` 30초 지연이 비즈니스에 영향 없는지 확인

3. **maxSize 프로덕션 모니터링**
   - `loginAttempts`(50,000), `productReviewVersion`(10,000) 실사용률 확인
   - 사용률 > 80% 캐시는 maxSize 확대 검토

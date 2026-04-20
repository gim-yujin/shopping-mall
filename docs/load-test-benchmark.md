# 부하 테스트 벤치마크 — 최적화 Before/After 비교

> **도구:** Grafana k6 (ramping-vus)
> **시나리오:** browse(100 VU), shopping(50 VU), coupon_rush(100 VU), mixed(180 VU)
> **측정:** 시나리오별 3회 반복, 평균값 기준
> **환경:** 로컬 단일 서버 (Spring Boot 3.4.1 + PostgreSQL 14.x)
> **참고:** 본 문서의 `@Async 검색로그` 항목은 Phase 6 시점의 역사적 측정값이다.
> 현재 검색 로그 구현은 Phase 19/20에서 `SearchLogBatchAccumulator` + 선택적 WAL로 전환되었다.
> Phase 21(COUNT 캐시 분리) 이후 500K 스케일 재측정은 §10에 별도 기록한다.

---

## 1) 테스트 조건 매트릭스

5가지 조건에서 동일한 k6 스크립트를 실행하여, 각 최적화 요소가 성능에 미치는 영향을 분리 측정했다.

| 조건 ID | Caffeine 캐시 | DB 인덱스 | OSIV | @Async 검색로그 | 보고서 |
|:--------|:--:|:--:|:--:|:--:|:--|
| **A. Baseline (최적화 전부 ON)** | ON | ON | OFF | ON | `k6_load_test_report.md` |
| **B. Cache OFF** | **OFF** | ON | OFF | ON | `k6_load_test_report_no_cache.md` |
| **C. Cache OFF + Index OFF** | **OFF** | **OFF** | OFF | ON | `k6_load_test_report_no_cache_no_index.md` |
| **D. Cache OFF + OSIV ON** | **OFF** | ON | **ON** | ON | `k6_load_test_report_OSIV_on.md` |
| **E. Cache OFF + OSIV ON + Async OFF** | **OFF** | ON | **ON** | **OFF** | `k6_load_test_report_async_off.md` |

> 조건 B~E는 Baseline에서 최적화를 하나씩 제거하며 측정한 결과다.
> 캐시를 끈 상태(B)를 기준선으로, OSIV ON(D)과 Async OFF(E)의 영향을 누적 관찰한다.

---

## 2) 핵심 지표 비교표 — HTTP p95 응답시간

| 시나리오 | A. Baseline | B. Cache OFF | C. No Cache+Index | D. +OSIV ON | E. +Async OFF |
|:---------|:--:|:--:|:--:|:--:|:--:|
| **browse** | **10.0ms** | 4,979ms | 12,750ms | 2,840ms | 5,010ms |
| **shopping** | **11.5ms** | 13.3ms | 14,290ms | 12.8ms | 15.1ms |
| **coupon_rush** | **14.2ms** | 1,442ms | 435.9ms | 1,380ms | 1,140ms |
| **mixed** | **9.5ms** | 3,890ms | 5,020ms | 3,180ms | 5,030ms |

### 읽는 법

- **A → B**: 캐시 OFF의 충격. browse p95가 10ms → 5초로 **500배** 증가.
- **B → C**: 인덱스까지 제거하면 browse p95 12.7초, shopping p95 14.3초 → **서비스 불능**.
- **B → D**: OSIV ON은 p95를 다소 개선하는 것처럼 보이나, **실패율이 증가**(아래 참조).
- **D → E**: Async OFF로 browse/mixed p95가 2.8초 → 5초로 **추가 악화**.

---

## 3) 처리량(RPS) 비교표

| 시나리오 | A. Baseline | B. Cache OFF | C. No Cache+Index | D. +OSIV ON | E. +Async OFF |
|:---------|:--:|:--:|:--:|:--:|:--:|
| **browse** | 44.1 | 22.6 | 7.36 | 27.2 | 23.8 |
| **shopping** | 31.4 | 31.2 | 6.21 | 31.2 | 31.2 |
| **coupon_rush** | 980.3 | 127.6 | 333.0 | 132.6 | 140.7 |
| **mixed** | 104.3 | 47.3 | 49.3 | 45.0 | 37.9 |

> ramping-vus 특성상 응답이 느려지면 RPS가 자연히 떨어진다.
> Baseline 대비 Cache OFF 시 browse RPS가 44 → 23으로 **48% 감소**.

---

## 4) HTTP 실패율 비교표

| 시나리오 | A. Baseline | B. Cache OFF | C. No Cache+Index | D. +OSIV ON | E. +Async OFF |
|:---------|:--:|:--:|:--:|:--:|:--:|
| **browse** | 0.00% | 0.00% | **91.66%** | **1.84%** | **4.32%** |
| **shopping** | 0.00% | 0.00% | **31.88%** | 0.00% | 0.00% |
| **coupon_rush** | 0.00% | 0.00% | 0.55% | 0.00% | 0.00% |
| **mixed** | 0.00% | **0.05%** | **23.35%** | **6.77%** | **8.19%** |

### 핵심 관찰

- **Baseline은 전 시나리오 0% 실패** — 캐시+인덱스+OSIV OFF+Async ON 조합이 안정적.
- **Cache OFF + No Index(C)**: browse 91.66% 실패, shopping 31.88% 실패 → 사실상 서비스 불능.
- **OSIV ON(D)**: p95만 보면 Cache OFF(B)보다 개선처럼 보이지만, browse 실패율 0→1.84%, mixed 0.05→6.77%로 **안정성이 악화**. OSIV가 DB 커넥션 점유 시간을 늘려 풀 고갈을 유발하는 것으로 추정.
- **Async OFF(E)**: mixed 실패율이 8.19%까지 상승. 검색 로그 동기 저장이 요청 스레드를 점유.

---

## 5) 비즈니스 지표 비교 — 주문/쿠폰 정합성

| 지표 | A. Baseline | B. Cache OFF | C. No Cache+Index | D. +OSIV ON | E. +Async OFF |
|:-----|:--:|:--:|:--:|:--:|:--:|
| **주문 생성 수 (shopping)** | 2,109건/회 | 2,108건/회 | 42건/회 | 2,108건/회 | 2,106건/회 |
| **주문 성공률 (shopping)** | 100% | 100% | 11.23% | 100% | 100% |
| **쿠폰 발급 (coupon_rush)** | 정확히 50장 | 정확히 50장 | 49.3장 | 정확히 50장 | 정확히 50장 |
| **쿠폰 초과 발급** | 없음 | 없음 | 없음 | 없음 | 없음 |

> 쿠폰 초과 발급은 어떤 조건에서도 발생하지 않았다. DB 행 잠금(`SELECT FOR UPDATE`) + UNIQUE 제약이 동시성을 올바르게 제어한다.

---

## 6) 최적화 요소별 효과 요약

### 6-1. Caffeine 캐시 (가장 큰 영향)

| 영향 지표 | 변화량 |
|:----------|:-------|
| browse p95 | 10ms → 4,979ms (**×498 악화**) |
| mixed p95 | 9.5ms → 3,890ms (**×409 악화**) |
| browse RPS | 44.1 → 22.6 (**-49%**) |
| coupon_rush RPS | 980 → 128 (**-87%**) |

**결론:** 읽기 트래픽(홈/상품목록/카테고리/검색)이 전체의 ~60%를 차지하는 이커머스에서, 캐시는 가장 ROI가 높은 최적화다. Caffeine의 11개 세분화 캐시(홈/카테고리/상품상세/검색/리뷰/쿠폰 등, TTL 1초~30분)가 DB 부하를 99%+ 흡수한다.

### 6-2. DB 인덱스

| 영향 지표 | 변화량 (B→C) |
|:----------|:-------|
| browse p95 | 4,979ms → 12,750ms (**×2.6 악화**) |
| browse 실패율 | 0% → **91.66%** |
| shopping 주문 수 | 2,108건 → **42건** (**-98%**) |
| shopping 실패율 | 0% → **31.88%** |

**결론:** 인덱스 없이는 쿼리 응답 시간이 수십 초로 상승하여 커넥션 풀이 고갈되고, 서비스가 사실상 정지한다. 현재 기준 60개 인덱스(일반 57 + UNIQUE 3)가 쿼리 성능의 기반이다.

### 6-3. OSIV (Open Session In View)

| 영향 지표 | 변화량 (B→D) |
|:----------|:-------|
| browse p95 | 4,979ms → 2,840ms (개선처럼 보임) |
| browse 실패율 | 0% → **1.84%** (악화) |
| mixed 실패율 | 0.05% → **6.77%** (악화) |

**결론:** OSIV ON은 Lazy Loading으로 일부 쿼리를 분산시켜 p95가 줄어 보이지만, DB 커넥션 점유 시간이 늘어나 **풀 고갈 → 요청 실패**로 이어진다. 부하 테스트에서 "p95 개선 + 실패율 악화"는 전형적인 리소스 포화 패턴이다.

### 6-4. Phase 6 `@Async` 검색 로그 비동기화

| 영향 지표 | 변화량 (D→E) |
|:----------|:-------|
| browse p95 | 2,840ms → 5,010ms (**+76% 악화**) |
| mixed p95 | 3,180ms → 5,030ms (**+58% 악화**) |
| mixed 실패율 | 6.77% → **8.19%** |

**결론:** 검색 로그 동기 저장이 HTTP 요청 스레드를 점유하여, 모든 읽기 엔드포인트의 tail latency가 ~5초(HikariCP connectionTimeout 추정)로 수렴한다.

---

## 7) 병목 위치 — TTFB 분석

모든 열화 조건에서 지연의 대부분은 **서버 처리 대기(TTFB = http_req_waiting)**에 집중되었다.

| 조건 | browse waiting p95 | mixed waiting p95 | connect p95 |
|:-----|:--:|:--:|:--:|
| A. Baseline | ~7ms | ~7ms | ~0ms |
| B. Cache OFF | ~4.9s | ~3.8s | ~0ms |
| D. +OSIV ON | ~2.8s | ~3.2s | ~0ms |
| E. +Async OFF | ~5.0s | ~5.0s | ~0ms |

> 네트워크(connect/send/recv)는 일관되게 미미 → **병목은 100% 서버 내부**(DB 쿼리, 커넥션 풀 대기, 스레드 큐잉).

---

## 8) Endpoint별 p95 상세 — Baseline vs 최악 조건

| Endpoint | A. Baseline | E. Async OFF (최악) | 배율 |
|:---------|:--:|:--:|:--:|
| GET / (홈) | ~7ms | 4,930ms | ×704 |
| GET /products | ~8ms | 4,960ms | ×620 |
| GET /products/:id | ~8ms | 8,730ms | ×1,091 |
| GET /search | ~8ms | 4,500ms | ×563 |
| GET /categories/:id | ~7ms | 3,760ms | ×537 |
| POST /orders | ~19ms | 10.4ms* | - |
| POST /coupons/issue | ~14ms | 910ms | ×65 |

> *shopping 단독은 캐시 불필요(세션 기반), Async 영향도 미미 → 주문 경로는 비관적 잠금으로 보호되어 일관되게 안정적.

---

## 9) 결론

| 최적화 요소 | 효과 크기 | 핵심 기여 |
|:-----------|:----------|:----------|
| **Caffeine 캐시** | p95 **×500 개선** | 읽기 트래픽 DB 부하 99%+ 흡수 |
| **DB 인덱스** | 없으면 **서비스 불능** | 쿼리 O(n) → O(log n) 전환 |
| **OSIV OFF** | 실패율 **6.77% → 0.05%** | DB 커넥션 조기 반환 |
| **@Async 검색 로그** | p95 **+76% 개선** | HTTP 스레드 블로킹 제거 |

**최적화가 모두 적용된 Baseline 상태에서:**
- 전 시나리오 HTTP 에러율 **0%**, Threshold **전부 PASS**
- browse/mixed p95 **10ms 이하**, shopping 주문 p95 **19ms**
- 쿠폰 러시(100 VU 동시 스파이크) **980 req/s** 처리, 초과 발급 **0건**

---

## 10) Phase 21 재측정 — 500K 스케일

Phase 21(COUNT 쿼리 공유 캐시 분리, [`analysis-product-list-count-cache-split.md`](./analysis-product-list-count-cache-split.md)) §4.2 "k6 재측정 미실시" 한계를 해소하기 위해 수행한 재측정 결과.

### 10-1. 환경

| 항목 | 값 |
|:-----|:---|
| DB | `shopping_mall_loadtest_db` (500K 시드; users 50K / products 50K / orders 500K / order_items 1.5M / reviews 200K) |
| 구성 요소 | Caffeine 캐시 ON, 인덱스 ON, OSIV OFF, 가상 스레드 ON (Phase 20), Phase 21 COUNT 캐시 분리 적용 |
| k6 | v1.7.1 |
| 시나리오 | browse (`load-test.js` SCENARIO=browse, 100 VU, 9분) |
| RUN_ID | `phase21_browse_20260421_011311` |
| 셋업 절차 | [`guide-loadtest-env-setup.md`](./guide-loadtest-env-setup.md) |

### 10-2. 결과

| 지표 | Phase 21 재측정 | §1의 A. Baseline (참고) |
|:-----|:--:|:--:|
| browse p50 | 7.7ms | - |
| browse p95 (overall) | **10.8ms** | 10.0ms |
| browse p99 | 59.1ms | - |
| browse 처리량 | 44.0 req/s | 44.1 req/s |
| HTTP 에러율 | 0.00% | 0.00% |
| 체크 통과율 | 100.00% | - |

### 10-3. 엔드포인트별 p95 / p99

| 엔드포인트 | p95 | p99 |
|:-----------|:--:|:--:|
| GET / | 10.7ms | 56.5ms |
| **GET /products** (Phase 21 대상) | **9.6ms** | 27.4ms |
| GET /products/:id | 31.3ms | 35.2ms |
| GET /search | 10.0ms | 163.2ms |
| GET /categories/:id | 10.2ms | 96.1ms |

### 10-4. 해석 — 무엇이 확인되었고 무엇이 확인되지 않았는가

**확인된 것**
- 500K 스케일에서도 Baseline과 동일한 수준의 p95(≈10ms)를 유지한다. Phase 21 이후 **회귀 없음**.
- `load-test-analysis.md`가 보고한 이전 측정의 p95 3.3~5.0s와 비교하면 수백 배 개선된 수치지만, 해당 수치는 스케일/하드웨어/누적 개선이 다른 환경이므로 Phase 21 단독 기여로 귀속할 수 없다.

**확인되지 않은 것 — Phase 21 단독 기여 격리**
- Phase 21 최적화의 핵심은 "`productList` 캐시 미스 storm 시 중복 COUNT 실행 제거"다. 이 효과는 다음 조건에서만 드러난다:
  - `productList`(2분 TTL) 만료 직후 여러 sort/page 조합이 동시에 미스 → 이전에는 조합 수만큼 COUNT 실행, 지금은 `productListCount`(10분 TTL, 단일 키)에서 1회 히트
- 본 9분 런 동안 `productListCount`(10분 TTL)는 기본적으로 히트 상태로 유지되므로, Phase 21 단독 효과는 관측되지 않는다.
- 단독 효과 재측정은 (a) 10분 이상 지속 실행 + (b) 캐시 강제 만료 후 재측정 스크립트가 필요하다 — 후속 과제로 보류한다.

### 10-5. Shopping 시나리오 — 500K 재측정 (부가 확인)

500K DB에서 shopping(로그인 → 장바구니 담기 → 체크아웃 → 주문 생성 → 주문 목록) 시나리오도 50 VU·9분으로 돌렸다. browse와 달리 여러 엔드포인트가 혼합되고 POST 쓰기 경로가 포함되므로, 재측정 중 드러난 운영·측정 이슈 2건을 기록으로 남긴다.

#### 10-5-1. 측정 수치 비교

| 지표 | 1차 런(인덱스 누락 상태) | 2차 런(인덱스 복구 후) | 3차 런(템플릿 픽스 후) |
|:-----|:----:|:----:|:----:|
| RUN_ID | `phase21_shopping_20260421_022632` | `phase21_shopping_indexed_20260421_024123` | `phase21_shopping_postfix_20260421_062628` |
| 처리량 | 16.9 req/s | 30.1 req/s | 29.9 req/s |
| overall p95 | **5459ms** | **13.4ms** | **13.2ms** |
| overall p99 | 7620ms | 15.4ms | 17.6ms |
| GET /orders p95 | 8410ms | 14.7ms | 10.4ms |
| POST /orders p95 | 1499ms | 13.5ms | 14.4ms |
| HTTP 에러율 | 14.09% | 16.98% | **4.09%** |
| 체크 통과율 | 88.18% | 88.58% | **100.00%** |
| order_ok | 93.77% (1068/1139) | 68.34% (1440/2107) | 68.25% (1439/2107) |
| `SpelEvaluationException` | 수 건 | 6478건 | **0건** |

#### 10-5-2. 1차 런이 느렸던 이유 — 운영 실수(인덱스 누락)

- 500K 생성기(`generate_dummy_data_500k.py`)는 속도를 위해 bulk load 직전 secondary index를 drop하고 마지막에 경고만 출력한 채 재생성은 스킵한다.
- 1차 런은 이 경고를 놓친 상태에서 실행되어, `orders(user_id, order_date DESC)` 등 다수 인덱스가 없는 채로 측정됐다. `EXPLAIN` 결과 `GET /orders`가 전체 Seq Scan으로 떨어져 p95 8.4s가 관측됐다.
- 2차 런은 `psql -f src/main/resources/schema.sql`로 58개 `CREATE INDEX` 재실행 + `VACUUM ANALYZE` 수행 후 재측정. p95가 세 자릿수 ms에서 **두 자릿수 ms**로 복구됐다.
- 이 운영 절차는 [`guide-loadtest-env-setup.md`](./guide-loadtest-env-setup.md) §4에 "§4-2-1 생성기 실행 후 스키마 재적용"으로 명시했다.

#### 10-5-3. 2차 런의 `order_ok` 하락은 성능 회귀가 아님 — 두 가지 확인된 원인

2차 런은 응답 시간이 20배 이상 개선됐는데도 `order_ok`가 93.77% → 68.34%로 낮아졌다. 앱 로그 분석 결과 원인은 성능 회귀가 아니라 별개의 두 가지 요인이다.

**(A) 사전 존재하는 템플릿 버그 — 3차 런에서 해소 확인**
- 2차 런 시점: 템플릿이 `order.orderStatusCode`와 `order.items`를 참조하지만, Phase 18 CQRS 전환 이후 `OrderListReadModel` record는 `orderStatus`, `itemCount`, `firstProductName`만 노출. 앱 로그에 `SpelEvaluationException: EL1008E: Property or field 'orderStatusCode' cannot be found`가 6478회 기록.
- 수정: 커밋 `0487a47`에서 템플릿 필드명을 DTO에 맞춰 동기화. `order.orderStatusCode` → `order.orderStatus`, `order.items` 루프 → `firstProductName` + "외 N건".
- 3차 런 검증: 동일 조건(500K DB·50 VU·9분)으로 재측정 결과 `SpelEvaluationException` **0건**, HTTP 에러율 16.98% → 4.09%, 체크 통과율 88.58% → 100.00%. 템플릿 버그가 전체 원인이었음이 확증.

**(B) `RateLimitFilter`의 ORDER 플랜 상한 — 3차 런에서 단일 원인으로 격리**
- `RateLimitPlan.ORDER`는 capacity 5, refill 5/60s (`RateLimitPlan.java:38`)이다. 정상 사용자가 분당 5건 이상 주문할 이유가 없다는 설계 의도에 따른다.
- 2차 런: 응답 시간이 빨라져 같은 VU가 분당 5건 이상 POST /orders를 보냈고, 앱 로그에 `event=rate_limit_exceeded plan=ORDER uri=/orders method=POST`가 733건 기록.
- 3차 런: rate_limit_exceeded 668건, `order_fail_http_4xx` 668건 — **정확히 일치**. 즉 3차 런의 `order_ok` 68.25% 하락은 **전적으로 ORDER 플랜 rate limit이 의도대로 발동한 결과**이며, 성능 회귀나 버그가 아니다.
- `order_ok`를 SLO 지표로 쓰려면 시나리오의 요청 간격을 ORDER 플랜(5/min)에 맞춰 늘려야 한다. 후속 과제로 분리(§10-5-4 참조).

#### 10-5-4. Shopping 재측정에서 확인된 것/되지 않은 것

**확인된 것**
- 500K 스케일에서 shopping 시나리오의 p95가 두 자릿수 ms 수준임 — Phase 21 이후 쓰기 경로 포함 시에도 회귀 없음.
- 생성기 이후 secondary index 재적용이 필수 운영 절차라는 점이 운영 경험으로 확정됨.
- 3차 런에서 템플릿 버그(A) 해소가 SpEL 에러 6478건 → 0건·HTTP 에러율 4.09%·체크 통과율 100%로 입증됨.
- `order_ok` 하락이 ORDER 플랜 rate limit 단일 원인(668/668 정확 일치)임이 로그로 격리됨.

**확인되지 않은 것 — 후속 과제**
- k6 shopping 시나리오의 per-VU 간격을 ORDER 플랜에 맞춰 조정한 뒤 `order_ok`가 100%에 수렴하는지는 별도 측정이 필요하다. (k6 스크립트 `load-test.js`의 `scenarioShopping` think time 조정)

### 10-6. 결론
- 본 재측정은 **Phase 21이 회귀를 일으키지 않았음**을 500K 스케일 browse + shopping 양쪽에서 확인한다. 이는 `analysis-product-list-count-cache-split.md` §4.2의 "재측정 미실시" 한계를 해소한다.
- Phase 21 단독 기여 격리 측정은 별도 실험 설계가 필요하므로 본 문서에는 포함하지 않는다.
- Shopping 시나리오에서 드러난 템플릿 버그(§10-5-3 A)는 커밋 `0487a47`에서 수정 + 3차 런으로 입증. rate limit 시나리오 불일치(§10-5-3 B)는 k6 스크립트 조정 후속 과제로 분리.

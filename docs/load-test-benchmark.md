# 부하 테스트 벤치마크 — 최적화 Before/After 비교

> **도구:** Grafana k6 (ramping-vus)
> **시나리오:** browse(100 VU), shopping(50 VU), coupon_rush(100 VU), mixed(180 VU)
> **측정:** 시나리오별 3회 반복, 평균값 기준
> **환경:** 로컬 단일 서버 (Spring Boot 3.4.1 + PostgreSQL 14.x)
> **참고:** 본 문서의 `@Async 검색로그` 항목은 Phase 6 시점의 역사적 측정값이다.
> 현재 검색 로그 구현은 Phase 19/20에서 `SearchLogBatchAccumulator` + 선택적 WAL로 전환되었다.

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

**결론:** 인덱스 없이는 쿼리 응답 시간이 수십 초로 상승하여 커넥션 풀이 고갈되고, 서비스가 사실상 정지한다. 57개 인덱스(일반 54 + UNIQUE 3)가 쿼리 성능의 기반이다.

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

# 쇼핑몰 k6 부하 테스트 분석 보고서 — Async OFF 영향 측정

- 작성일: 2026-02-25 (KST)
- 실험 목적: **(캐시 OFF, 인덱스 ON, OSIV ON)**을 고정한 상태에서 **Async OFF**가 성능/안정성에 주는 영향을 수치로 확인
- 반복 측정: 시나리오별 **3회 반복 측정** (모든 값은 유효, 오차 포함 가능 → **배제/제외 없음**)

---

## 1. 테스트 구성 요약

### 1.1 고정/변경 변수

| 항목 | 설정 |
|:--|:--|
| Cache | OFF |
| DB Index | ON |
| OSIV | ON |
| Async | **OFF (이번 실험)** |

### 1.2 시나리오 및 사용한 RUN

사용자가 지정한 RUN만 집계했습니다(모든 값 포함).

| Scenario | RUN |
|:--|:--|
| browse | 3, 4, 5 |
| shopping | 1, 2, 3 |
| coupon_rush | 1, 2, 3 |
| mixed | 2, 3, 4 |

### 1.3 k6 시나리오 형태(스크립트 기준 요약)

- browse: ramping-vus (최대 100 VU, 약 9분)
- shopping: ramping-vus (최대 50 VU, 약 9분)
- coupon_rush: ramping-vus spike (최대 100 VU, 약 40초)
- mixed: browse/shopping/coupon/social 혼합 (최대 180 VU, 약 10분 30초)

> 참고: ramping-vus는 **“주어진 시간 동안 VU가 가능한 한 많은 iteration을 반복”**하는 방식이라, **응답이 느려질수록 RPS가 자연히 떨어지는 구조**입니다.  
> (즉, RPS는 타겟이 아니라 결과 값)

---

## 2. Executive Summary (핵심 결론)

### 결론 요약
- **Shopping(주문 플로우)**는 Async OFF 상태에서도 **매우 안정적**  
  - p95 ≈ **15ms**, HTTP 실패율 **0%**, Threshold **12/12 전부 PASS(3/3)**  
- 반면 **Browse / Mixed**에서 **치명적인 Tail latency + 실패율 증가**가 관찰됨  
  - browse: p95 ≈ **5.01s**, HTTP 실패율 평균 **4.319%**, Checks 평균 **95.681%**  
  - mixed: p95 ≈ **5.03s**, HTTP 실패율 평균 **8.193%**, Checks 평균 **92.106%**  
  - 특히 mixed는 **주문/장바구니 성공률이 80%대**까지 하락
- **네트워크/클라이언트 구간보다 서버 처리 대기(http_req_waiting)가 병목**  
  - browse/mixed에서 waiting p95가 각각 **~5초 수준**으로 지배적

---

## 3. 시나리오별 전체 성능 요약 (3회 평균 + 범위)

| scenario | runs | duration | max_vu | rps | http_med | http_p95 | http_p99 | http_max | http_failed | checks | thresholds |
| :-- | :-- | :-- | --: | --: | --: | --: | --: | --: | --: | --: | :-- |
| browse | 3,4,5 | 545s (543s~546s) | 100 | 23.8 (23.2~24.4) | 342ms | 5.01s | 8.73s | 10.00s | 4.319% | 95.681% | 2/9 (all-pass 0/3) |
| shopping | 1,2,3 | 545s (541s~547s) | 50 | 31.2 (30.9~31.5) | 7.07ms | 15.1ms | 18.1ms | 394ms | 0.000% | 100.000% | 12/12 (all-pass 3/3) |
| coupon_rush | 1,2,3 | 40s (40s~40s) | 100 | 140.7 (140.2~141.6) | 455ms | 1.14s | 2.19s | 3.38s | 0.000% | 100.000% | 6/7 (all-pass 0/3) |
| mixed | 2,3,4 | 634s (633s~635s) | 180 | 37.9 (37.2~38.3) | 407ms | 5.03s | 9.22s | 10.01s | 8.193% | 92.106% | 2/23 (all-pass 0/3) |

> 표의 http_med/p95/p99/max는 **전체 요청의 http_req_duration** 기준입니다.

---

## 4. 시나리오별 상세 분석

## 4.1 browse (RUN 3,4,5)

### (1) Run별 요약

| run | duration | max_vu | rps | http_med | http_p95 | http_p99 | http_max | http_failed | checks | thresholds |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 3 | 546s | 100 | 23.2 | 352ms | 5.01s | 9.01s | 10.00s | 4.698% | 95.302% | 2/9 |
| 4 | 545s | 100 | 24.4 | 348ms | 5.01s | 8.35s | 10.00s | 3.851% | 96.149% | 2/9 |
| 5 | 543s | 100 | 23.9 | 326ms | 5.01s | 8.83s | 10.01s | 4.408% | 95.592% | 2/9 |

### (2) Endpoint별 p95/p99 (3회 평균/범위)

| endpoint | p95_avg | p95_range | p99_avg |
| :-- | --: | :-- | --: |
| GET /products/:id | 8.73s | 8.35s~9.02s | 9.73s |
| GET /products | 4.96s | 4.88s~5.01s | 5.02s |
| GET / | 4.93s | 4.78s~5.00s | 5.61s |
| GET /search | 4.50s | 4.42s~4.63s | 5.01s |
| GET /categories/:id | 3.76s | 3.59s~3.89s | 4.28s |

**관찰 포인트**
- **상품 상세(GET /products/:id)**가 가장 느림  
  - endpoint p95 평균 **8.73s** (최대 9.02s까지)
- 나머지 탐색성 endpoint도 p95가 **3.76s~4.96s**로 전반적 저하

### (3) JS timer 기반 browse_duration (홈페이지 그룹 측정)

| run | browse_duration_med(JS timer) | browse_duration_p95(JS timer) | browse_duration_p99(JS timer) |
| --: | --: | --: | --: |
| 3 | 887ms | 4.99s | 5.66s |
| 4 | 993ms | 4.78s | 5.58s |
| 5 | 959ms | 5.00s | 5.60s |

---

## 4.2 shopping (RUN 1,2,3)

### (1) Run별 요약

| run | duration | max_vu | rps | http_med | http_p95 | http_p99 | http_max | http_failed | checks | thresholds |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 1 | 546s | 50 | 31.2 | 7.05ms | 15.0ms | 18.2ms | 404ms | 0.000% | 100.000% | 12/12 |
| 2 | 547s | 50 | 30.9 | 7.09ms | 15.0ms | 18.1ms | 398ms | 0.000% | 100.000% | 12/12 |
| 3 | 541s | 50 | 31.5 | 7.06ms | 15.1ms | 18.1ms | 381ms | 0.000% | 100.000% | 12/12 |

### (2) 비즈니스/커스텀 메트릭

| run | login_p95 | order_create_p95 | orders_created | login_ok | cart_ok | order_ok |
| --: | --: | --: | --: | --: | --: | --: |
| 1 | 717ms | 18.0ms | 2114 | 100.00% | 100.00% | 100.00% |
| 2 | 711ms | 18.0ms | 2094 | 100.00% | 100.00% | 100.00% |
| 3 | 705ms | 18.0ms | 2111 | 100.00% | 100.00% | 100.00% |

### (3) Endpoint별 p95/p99 (3회 평균/범위)

| endpoint | p95_avg | p95_range | p99_avg |
| :-- | --: | :-- | --: |
| GET /orders | 11.4ms | 11.2ms~11.5ms | 12.5ms |
| POST /orders | 10.4ms | 10.4ms~10.5ms | 11.9ms |
| GET /orders/checkout | 9.38ms | 9.34ms~9.45ms | 10.5ms |
| GET /cart | 8.27ms | 8.17ms~8.32ms | 9.19ms |
| POST /cart/add | 7.40ms | 7.36ms~7.47ms | 8.27ms |

**관찰 포인트**
- 주문/장바구니 관련 endpoint는 p95가 **7~12ms 수준**
- 로그인 p95도 **~0.71s 수준**으로 수용 가능(시나리오 특성상 VU별 1회 로그인)

---

## 4.3 coupon_rush (RUN 1,2,3)

### (1) Run별 요약

| run | duration | max_vu | rps | http_med | http_p95 | http_p99 | http_max | http_failed | checks | thresholds |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 1 | 40s | 100 | 141.6 | 447ms | 1.18s | 2.21s | 3.34s | 0.000% | 100.000% | 6/7 |
| 2 | 40s | 100 | 140.4 | 454ms | 1.17s | 2.15s | 3.51s | 0.000% | 100.000% | 6/7 |
| 3 | 40s | 100 | 140.2 | 464ms | 1.08s | 2.21s | 3.29s | 0.000% | 100.000% | 6/7 |

### (2) 비즈니스/커스텀 메트릭

| run | login_p95 | coupon_issue_p95(JS timer) | coupon_server_ok | coupons_issued | coupons_failed | sold_out |
| --: | --: | --: | --: | --: | --: | --: |
| 1 | 4.41s | 1.63s | 100.00% | 50 | 1745 | 1745 |
| 2 | 4.39s | 1.57s | 100.00% | 50 | 1731 | 1731 |
| 3 | 4.39s | 1.71s | 100.00% | 50 | 1727 | 1727 |

### (3) Endpoint별 p95/p99 (3회 평균/범위)

| endpoint | p95_avg | p95_range | p99_avg |
| :-- | --: | :-- | --: |
| POST /coupons/issue | 910ms | 907ms~913ms | 1.34s |

**관찰 포인트**
- HTTP 레벨에서 **POST /coupons/issue endpoint p95는 ~0.91s로 1초 threshold를 만족**
- 하지만 **coupon_duration(JS timer) p95는 1.57~1.71s**로 threshold 실패  
  - (리다이렉트/후속 요청 포함 “체감 발급 시간”은 더 길게 관측되는 구조)

---

## 4.4 mixed (RUN 2,3,4)

### (1) Run별 요약

| run | duration | max_vu | rps | http_med | http_p95 | http_p99 | http_max | http_failed | errors_rate | checks | thresholds |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 2 | 635s | 180 | 38.3 | 387ms | 5.07s | 9.30s | 10.01s | 7.579% | 10.145% | 92.918% | 2/23 |
| 3 | 634s | 180 | 37.2 | 435ms | 5.01s | 9.29s | 10.01s | 8.723% | 11.457% | 91.821% | 2/23 |
| 4 | 633s | 180 | 38.3 | 398ms | 5.01s | 9.06s | 10.01s | 8.277% | 11.999% | 91.581% | 2/23 |

### (2) 비즈니스/커스텀 메트릭

| run | login_p95 | order_p95(JS timer) | coupon_p95(JS timer) | browse_p95(JS timer) | login_ok | cart_ok | order_ok | orders (ok/fail) | coupons (issued/fail) | auth_failures | cart_5xx | order_5xx |
| --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| 2 | 5.01s | 9.62s | 1.14s | 5.36s | 96.00% | 81.76% | 84.12% | 821/155 | 30/927 | 298 | 119 | 96 |
| 3 | 5.72s | 9.84s | 1.34s | 5.05s | 96.05% | 81.47% | 84.13% | 790/149 | 30/841 | 293 | 116 | 91 |
| 4 | 5.01s | 9.56s | 1.15s | 5.18s | 93.33% | 79.55% | 81.28% | 799/184 | 30/869 | 505 | 101 | 84 |

### (3) Endpoint별 p95/p99 (3회 평균/범위)

| endpoint | p95_avg | p95_range | p99_avg |
| :-- | --: | :-- | --: |
| GET /products/:id | 9.49s | 9.40s~9.58s | 9.95s |
| GET / | 5.20s | 5.05s~5.36s | 5.50s |
| GET /products | 5.01s | 5.01s~5.01s | 5.08s |
| GET /search | 5.01s | 5.01s~5.01s | 5.10s |
| POST /wishlist/toggle | 5.01s | 5.01s~5.01s | 5.01s |
| GET /wishlist | 5.01s | 5.00s~5.01s | 5.01s |
| GET /mypage | 5.01s | 5.01s~5.01s | 5.01s |
| GET /categories/:id | 5.01s | 5.01s~5.01s | 5.01s |
| POST /cart/add | 5.01s | 5.01s~5.01s | 5.01s |
| GET /orders | 5.00s | 4.99s~5.00s | 5.01s |
| POST /orders | 4.99s | 4.98s~5.00s | 5.01s |
| GET /cart | 4.96s | 4.88s~5.01s | 5.01s |
| GET /orders/checkout | 4.95s | 4.86s~5.00s | 5.01s |
| POST /coupons/issue | 645ms | 619ms~693ms | 854ms |

### (4) 실패 원인 카운터 요약(3회)

| metric | runs | avg | min | max |
| :-- | :-- | --: | --: | --: |
| auth_failures | 298, 293, 505 | 365.3 | 293 | 505 |
| cart_fail_auth | 59, 58, 100 | 72.3 | 58 | 100 |
| cart_fail_http_5xx | 119, 116, 101 | 112.0 | 101 | 119 |
| order_fail_auth | 59, 58, 100 | 72.3 | 58 | 100 |
| order_fail_http_5xx | 96, 91, 84 | 90.3 | 84 | 96 |
| coupon_fail_sold_out | 927, 841, 869 | 879.0 | 841 | 927 |

**관찰 포인트**
- mixed는 **탐색 트래픽 + 구매/쿠폰/소셜**이 동시에 들어오면서,
  - 전체 p95가 **~5초**, p99가 **~9초**까지 상승
  - order_ok가 **81~84%**, cart_ok가 **79~82%**로 하락
- 실패 원인 중 **cart/order 5xx + auth redirect(인증 실패/세션 이슈로 추정)**가 크게 관측됨

---

## 5. 병목 위치 추정 — HTTP phase(p95)

> http_req_duration은 sending + waiting + receiving으로 구성되며, 이 중 **waiting(서버 처리/대기)**가 대부분을 차지하는지 확인합니다.

| scenario | blocked_p95 | connect_p95 | send_p95 | waiting_p95 | recv_p95 |
| :-- | --: | --: | --: | --: | --: |
| browse | 0.19ms | 0.12ms | 0.05ms | 5.01s | 8.08ms |
| shopping | 0.02ms | 0.00ms | 0.05ms | 12.4ms | 2.65ms |
| coupon_rush | 0.01ms | 0.00ms | 0.04ms | 1.14s | 7.12ms |
| mixed | 0.29ms | 0.23ms | 0.06ms | 5.03s | 7.39ms |

**해석**
- browse / mixed에서 waiting_p95가 각각 **~5초**로 사실상 전체를 설명  
  → 네트워크(connect/send/recv) 문제가 아니라 **서버 내부(스레드/DB/락/풀) 대기** 가능성이 높음

---

## 6. Threshold 결과(어떤 조건이 FAIL 되었나)

> 시나리오별로 “무엇이 FAIL인지”를 명확히 남깁니다.

### 6.1 browse Threshold (RUN 3/4/5 공통)

| metric | threshold | run3 | run4 | run5 |
| :-- | :-- | :-- | :-- | :-- |
| checks | rate>0.99 | FAIL | FAIL | FAIL |
| errors | rate<0.1 | PASS | PASS | PASS |
| http_req_duration | p(95)<3000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:category} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:home} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:product_detail} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:products_list} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:search} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_failed | rate<0.05 | PASS | PASS | PASS |

### 6.2 coupon_rush Threshold (RUN 1/2/3 공통)

| metric | threshold | run1 | run2 | run3 |
| :-- | :-- | :-- | :-- | :-- |
| checks | rate>0.99 | PASS | PASS | PASS |
| coupon_duration | p(95)<1000 | FAIL | FAIL | FAIL |
| coupon_server_ok | rate>0.99 | PASS | PASS | PASS |
| errors | rate<0.1 | PASS | PASS | PASS |
| http_req_duration{endpoint:coupon_issue} | p(95)<1000 | PASS | PASS | PASS |
| http_req_failed | rate<0.05 | PASS | PASS | PASS |
| login_ok | rate>0.99 | PASS | PASS | PASS |

### 6.3 mixed Threshold (RUN 2/3/4)

| metric | threshold | run2 | run3 | run4 |
| :-- | :-- | :-- | :-- | :-- |
| cart_landing_ok | rate>0.99 | FAIL | FAIL | FAIL |
| checks | rate>0.99 | FAIL | FAIL | FAIL |
| coupon_duration | p(95)<1000 | FAIL | FAIL | FAIL |
| coupon_server_ok | rate>0.99 | PASS | PASS | PASS |
| errors | rate<0.1 | FAIL | FAIL | FAIL |
| http_req_duration | p(95)<3000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:cart_add} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:cart_view} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:category} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:checkout} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:coupon_issue} | p(95)<1000 | PASS | PASS | PASS |
| http_req_duration{endpoint:home} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:mypage} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:order_create} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:order_list} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:product_detail} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:products_list} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:search} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:wishlist_page} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_duration{endpoint:wishlist_toggle} | p(95)<1000 | FAIL | FAIL | FAIL |
| http_req_failed | rate<0.05 | FAIL | FAIL | FAIL |
| login_ok | rate>0.99 | FAIL | FAIL | FAIL |
| order_ok | rate>0.99 | FAIL | FAIL | FAIL |

---

## 7. Async OFF 영향 해석 (이전 OSIV ON 보고서와 비교)

> **전제:** 사용자의 설명대로 “캐시 OFF / 인덱스 ON / OSIV ON 고정”에서 **Async만 토글**되었다고 보고,  
> 이전 결과(OSIV ON 보고서) 대비 이번(Async OFF) 결과를 비교합니다.  
> *(단, 테스트 환경/데이터/실행 타이밍이 달라질 수 있으므로 절대값 비교는 참고용)*

| scenario | OSIV_ON (prev) p95 | Async_OFF (now) p95 | Δp95 | OSIV_ON (prev) rps | Async_OFF (now) rps | Δrps | OSIV_ON fail% | Async_OFF fail% | Δfail%p |
| :-- | --: | --: | --: | --: | --: | --: | --: | --: | --: |
| browse | 2.84s | 5.01s | 2.17s | 27.2 | 23.8 | -3.4 | 1.837% | 4.319% | +2.482% |
| shopping | 12.8ms | 15.1ms | 2.26ms | 31.2 | 31.2 | +0.0 | 0.000% | 0.000% | +0.000% |
| coupon_rush | 1.38s | 1.14s | -237.49ms | 132.6 | 140.7 | +8.1 | 0.000% | 0.000% | +0.000% |
| mixed | 3.18s | 5.03s | 1.85s | 45.0 | 37.9 | -7.1 | 6.772% | 8.193% | +1.421% |

**핵심 해석**
- **Browse/Mixed에서 Async OFF는 p95를 크게 악화**시키고(약 +1.85~+2.17s), 실패율도 증가
- shopping은 영향이 미미(수 ms 수준)
- coupon_rush는 p95가 개선된 것처럼 보이지만,  
  **시나리오 구성(예: 쿠폰 페이지 스킵 여부) 차이 가능성**이 있어 단정은 주의

---

## 8. 권장 액션 플랜 (우선순위)

### P0. “5초대 p95 고정” 현상 원인 규명
- browse/mixed에서 다수 endpoint p95가 **~5.0초로 ‘붙는’ 현상**이 있음  
  → 아래 항목을 우선 확인 권장
  1) **DB 커넥션 풀(HikariCP) 고갈/대기** 여부 (connectionTimeout 값이 5s 근처인지 포함)  
  2) 서버 스레드 풀 고갈/큐잉(Tomcat worker thread)  
  3) 특정 락(쿠폰/재고/주문) 경합이 browse까지 전파되는지  
  4) GC/CPU 스로틀링

### P0. mixed에서 인증 실패/리다이렉트(auth_failures) 원인 확인
- auth_failures가 RUN 4에서 특히 큼(505)  
  - 세션/쿠키/CSRF 토큰 처리 경합, 혹은 서버 오류로 인한 강제 리다이렉트 가능
- 애플리케이션 로그에서 **/auth/login 리다이렉트 발생 시점의 직전 예외** 추적

### P1. Async OFF로 악화된 구간이 “어떤 작업” 때문인지 분리
- Async가 꺼지면서 요청 스레드에서 수행되는 부가 작업이 있다면(예: 통계/로그/랭킹/이벤트 발행),
  - 해당 작업을 **다시 비동기화(@Async 또는 메시지 큐/이벤트)** 하되
  - 실행 풀(TaskExecutor) 분리/사이징으로 “비동기 풀 포화 → 전체 지연”이 없도록 설계 필요

### P2. 부하 테스트 설계 보강
- ramping-vus는 “RPS가 결과”라서, 병목 구간에서 실제 유입(RPS)이 감소합니다.
- **고정 RPS 기반(예: ramping-arrival-rate)** 시나리오도 함께 구성하면 “용량 한계점”이 더 선명하게 드러납니다.

---

## 부록 A. 원본 데이터 처리 방식

- 각 시나리오별 3회 측정값에 대해:
  - **평균(mean)** 과 **범위(min~max)** 를 함께 표기
  - 이상치 제거/클리닝 없이 **원본 전체 사용** (사용자 요구사항 준수)


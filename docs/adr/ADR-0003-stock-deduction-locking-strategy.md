# ADR-0003: 재고 차감 동시성 제어는 비관적 잠금(Pessimistic Lock)을 사용한다

- 상태: 채택
- 날짜: 2026-03-24
- 관련 코드: `com.shop.domain.order.service.stock` (벤치마크 전략 구현)

## 배경(Context)

- 주문 생성 시 재고 차감은 시스템에서 가장 경합이 높은 연산이다.
- 인기 상품에 다수의 사용자가 동시에 주문하면 과매도(overselling)가 발생할 수 있다.
- 동시성 제어 전략에 따라 처리량·레이턴시·정합성이 크게 달라지므로, 실측 데이터에 기반한 선택이 필요했다.

## 결정(Decision)

- 재고 차감에는 **비관적 잠금(PESSIMISTIC_WRITE, `SELECT ... FOR UPDATE`)**을 사용한다.
- `OrderStockProcessor.deductStockAndBuildOrderLines()`에서 `findAllByIdInWithLock()`으로 대상 상품 행을 잠근 뒤, 엔티티를 통해 재고를 차감한다.

## 대안(Alternatives)

세 가지 전략을 별도 컴포넌트로 구현하고, Low(10스레드/50상품)/High(30스레드/1상품) 경합 시나리오에서 벤치마크를 수행했다.

### 벤치마크 결과

```
Strategy         Contention   Threads  Ops/sec  Success%    P50(ms)    P95(ms)    P99(ms)
----------------------------------------------------------------------------------------------------
V1-Pessimistic   Low               10     2409    100.0%       3.51       5.40      17.98
V1-Pessimistic   High              30      567    100.0%      57.20      64.54      66.07
V2-Optimistic    Low               10     4450     20.6%       1.97       3.70       5.83
V2-Optimistic    High              30     3077      6.3%       8.89      13.99      15.00
V3-CAS           Low               10     4560    100.0%       1.67       3.37       9.44
V3-CAS           High              30      981    100.0%      16.20      73.68     131.79
----------------------------------------------------------------------------------------------------
  Warmup: 1 round(s), Measure: 3 round(s) (median selected)
```

### 1. V1 — 비관적 잠금 (채택)

`SELECT ... FOR UPDATE`로 행을 잠근 뒤 엔티티에서 재고를 차감한다.

- **장점**: 성공률 100%. 경합 수준에 관계없이 요청이 반드시 처리된다(대기 후 진행). JPA 엔티티를 통해 주문 라인 생성·할인 계산 등 복합 비즈니스 로직을 자연스럽게 수행할 수 있다.
- **단점**: High 경합 시 P99 레이턴시가 361ms로 가장 높다. 잠금 대기 시간이 길어질수록 처리량이 감소한다.

### 2. V2 — 낙관적 잠금 + 재시도 (미채택)

`@Version` 필드로 커밋 시 충돌을 감지하고, 지수 백오프로 최대 5회 재시도한다.

- **장점**: Low 경합에서 처리량(4,306 ops/sec)이 가장 높다. 잠금 대기가 없어 P50 레이턴시가 가장 낮다.
- **단점**: **성공률이 치명적으로 낮다** (Low 19.8%, High 7.0%). 재고 차감처럼 다수의 트랜잭션이 동일 행을 연속적으로 수정하는 시나리오에서는 `@Version`이 매 커밋마다 증가하므로, 재시도마다 다시 충돌할 확률이 높다. 재시도 5회를 소진하면 요청이 유실된다.

### 3. V3 — Atomic CAS UPDATE (미채택, 유보)

`UPDATE ... SET stock = stock - ? WHERE stock >= ?` 단일 SQL로 원자적 차감.

- **장점**: 성공률 100%. Low 경합에서 V1보다 59% 높은 처리량(3,823 vs 2,405). High 경합에서도 V1보다 51% 높은 처리량(931 vs 615). 엔티티 로딩·영속성 컨텍스트 비용이 없다.
- **단점**: JPA 영속성 컨텍스트를 우회하므로, 현재 `OrderStockProcessor`의 비즈니스 로직(주문 라인 생성, 할인 계산, InventorySnapshot 기록)을 순수 SQL로 재구현해야 한다. 또한 1차 캐시와의 불일치로 같은 트랜잭션 내에서 엔티티를 다시 읽으면 stale 데이터를 볼 수 있다.

## 근거(Why)

1. **정합성 > 처리량**: 재고 차감은 과매도를 허용할 수 없는 연산이다. 성공률 100%가 최우선 요구사항이며, V2는 이를 충족하지 못한다.

2. **V3가 처리량에서 우위이나, 아키텍처 비용이 크다**: V3(CAS)는 V1보다 모든 시나리오에서 처리량이 높고 성공률도 100%이다. 그러나 현재 재고 차감은 단독 연산이 아니라, 주문 라인 생성·할인 계산·쿠폰 적용·포인트 차감이 하나의 트랜잭션에서 엔티티를 공유하며 수행된다. V3를 채택하려면 이 전체 흐름을 SQL 기반으로 재설계해야 하며, 이는 현재 단계에서 과도한 복잡도 증가이다.

3. **V1의 레이턴시는 수용 가능하다**: High 경합(30스레드/1상품) 시나리오는 인위적인 극단 조건이다. 실제 서비스에서는 상품이 분산되어 있으므로 Low 경합에 가까우며, P99 18ms는 충분히 수용 가능한 수준이다.

4. **향후 V3 전환 여지 보존**: V3 전략은 독립 컴포넌트로 유지하며, 향후 주문 생성 흐름이 CQRS 쓰기 모델로 분리될 경우 전환을 재검토한다.

## 결과(Consequences)

- 기대 효과:
  - 재고 정합성 100% 보장 (과매도 방지)
  - 기존 비즈니스 로직(주문 라인·할인·쿠폰·포인트)과의 자연스러운 통합 유지
- 감수해야 할 비용/리스크:
  - 인기 상품 집중 시 P99 레이턴시 상승 (벤치마크 기준 최대 66ms)
  - 데드락 방지를 위한 잠금 순서 관리 필요 (productId 오름차순)
- 후속 작업:
  - [x] V1/V2/V3 전략 벤치마크 구현 및 실행 (`StockDeductionBenchmarkTest`)
  - [x] burst-extreme 시나리오로 V1 레이턴시 붕괴 곡선 실측 (`StockDeductionBurstBenchmarkTest`)
  - [ ] 주문 생성 흐름 CQRS 쓰기 모델 분리 시 V3(CAS) 전환 재검토

## 한계 조건(Limits)

위 채택 결정은 "정상 트래픽 + 인기 상품의 경합"이라는 전제 위에 있다. 단일 상품에 동시
요청이 집중되는 *burst-extreme* 상황에서는 V1의 거동이 어떻게 변하는지 별도로 측정해야
"이 결정이 어디까지 유효한가"가 명확해진다.

### Burst-Extreme 벤치마크 결과

`StockDeductionBurstBenchmarkTest` — 1상품 × {30, 100, 300} 스레드 × 5 ops/thread.
HikariCP 풀 크기 20, PostgreSQL `lock_timeout = 3s`. 워밍업 1라운드 + 측정 3라운드(중앙값).

```
Strategy          Threads  Ops/sec  Success%   LockT/O   PoolT/O    P50(ms)    P95(ms)    P99(ms)
-------------------------------------------------------------------------------------------------------------------
V1-Pessimistic         30      339    100.0%         0         0      89.30      98.82     100.25
V1-Pessimistic        100      670    100.0%         0         0     145.52     162.54     165.77
V1-Pessimistic        300      794    100.0%         0         0     281.78     814.62    1216.51
V3-CAS                 30     1240    100.0%         0         0      15.61      51.44      84.01
V3-CAS                100     1228    100.0%         0         0      26.25     208.85     314.23
V3-CAS                300     1243    100.0%         0         0      43.66     643.33     917.04
```

### 무엇이 먼저 깨지나

- **V1은 P99 레이턴시가 먼저 무너진다.** 스레드 30→300에서 P99가 100ms → 1217ms로
  **약 12배** 증가했다. 같은 구간에서 처리량은 339 → 794 ops/sec로 우상향하지만, 이는
  단일 행에 직렬화된 큐가 길어지면서 *batch 효과*가 나타난 것이지 시스템이 더 잘
  버틴다는 의미가 아니다. 측정 환경의 `lock_timeout = 3s`를 P99가 추월하는 지점에서
  V1은 `LockT/O` 실패가 발생하기 시작하며, 그 시점부터 성공률이 100% 미만으로 떨어진다.
  본 측정에서는 300스레드에서 P99가 1.2s로 임계치(3s)에 약 40%까지 접근했다.
- **V3는 같은 구간에서 평탄하다.** 처리량은 1240 ops/sec 근처에서 saturate되고, P99는
  84 → 314 → 917ms로 증가하지만 V1보다 일관되게 낮으며 lock-timeout을 본질적으로
  유발하지 않는다(행 잠금이 없으므로). pool-timeout은 두 전략 모두 본 측정에서는 발생하지
  않았다 — 즉 **현 부하 수준에서는 V1이 깨지는 원인은 풀 고갈이 아니라 row-lock 큐의
  레이턴시 누적**이다.

### 1억 동시 시나리오에 대한 답

동일 상품에 대해 N개 트랜잭션이 동시에 비관적 락을 시도할 때, `SELECT ... FOR UPDATE`는
(N−1)개를 직렬 큐에 세우고, 이들은 (a) DB의 `lock_timeout` 또는 (b) 애플리케이션의
HikariCP `connection-timeout` 중 더 빨리 닿는 한계에 부딪힌다. 1억이라는 숫자는 단일 DB
인스턴스가 처리할 수 있는 단계를 넘어선 가정이지만, 위 벤치마크가 보여주듯 그 *훨씬
이전*(수백 스레드)에서 이미 V1의 P99는 비선형으로 폭증한다. 따라서 **burst가 일상적인
경로**는 처음부터 별도 도메인(`com.shop.domain.flashsale`)으로 분리되어 CAS를 운영
기본값으로 쓰며, **일반 주문 경로**는 burst가 비일상적이라는 전제 위에 비관적 락을
유지한다. 이는 결정을 *번복*하는 것이 아니라 결정의 *적용 범위*를 명시하는 것이다.

### Hot product가 일반 경로에서 발생했을 때의 처리

만약 일반 상품 중 하나가 burst hot이 된다면(예: 광고/SNS 노출로 트래픽이 단일 상품에
폭주), 그 상품은 사실상 플래시 세일 후보다. 정답은 *일반 주문의 락 전략을 동적으로
바꾸는 것*(hybrid lock)이 아니라, **운영 시점에 해당 상품을 플래시 세일로 등록**해
검증된 5계층 admission control(RateLimit / Status cache / Idempotency / CAS / Order)로
태우는 것이다. 코드 경로 분기를 두 도메인 모두에 도입하는 것은 *동일한 문제*에 대해
*두 개의 해법*을 유지하는 비용이 크다.

## v2 실험 — Redis 백엔드 (운영 미도입, 학습/측정 전용)

본 결정의 적용 범위 내에서 V1 의 천장이 ~1,200 ops/sec(V3-CAS) 라는 점은 명확하다.
"그 천장을 어디까지 끌어올릴 수 있는가" 를 직접 측정하기 위해 v2 경로(Redis Lua CAS)
를 실험적으로 추가했다. **포트폴리오 코드는 v1 그대로**이며 v2 는 `--spring.profiles.active=redis`
일 때만 빈이 등록되도록 격리되어 있다(@ConditionalOnProperty + @Primary 빈 치환).

### V1 vs V3 vs V4 burst 비교

`RedisStockDeductionBenchmarkTest` — 동일 워크로드(1상품 × {30, 100, 300} 스레드 × 5 ops).

```
Strategy          Threads   Ops/sec  Success%    P50(ms)    P95(ms)    P99(ms)
---------------------------------------------------------------------------------------------------------
V1-Pessimistic         30       384    100.0%      79.27      88.03      88.79
V1-Pessimistic        100       666    100.0%     148.74     161.45     163.10
V1-Pessimistic        300       778    100.0%     347.13     761.01    1130.02
V3-CAS                 30      1228    100.0%      15.44      46.93      70.67
V3-CAS                100      1252    100.0%      32.25     183.43     256.47
V3-CAS                300      1265    100.0%      26.92     649.38     962.36
V4-Redis               30     12771    100.0%       1.88       3.33       3.35
V4-Redis              100     19705    100.0%       4.76       7.70       7.79
V4-Redis              300     13627    100.0%      20.53      29.59      31.60
```

### 관찰

- **처리량**: V4 가 V3 대비 100스레드에서 **약 15.7배** (19,705 vs 1,252 ops/sec).
  Redis 단일 스레드 + 메모리 기반 Lua 의 우위가 실측으로 드러난다.
- **레이턴시**: V4 P99 가 300스레드에서 **31.6ms** — V3 의 **962ms** 대비 약 30배 낮고,
  V1 의 **1,130ms** 대비 약 35배 낮다. row-lock 큐 / 디스크 fsync 가 사라진 효과.
- **300스레드에서 V4 처리량 dip**(13,627 < 19,705 @ 100): Lettuce 풀(32) 포화 + JVM 스레드
  컨텍스트 스위칭 비용이 Redis 자체 한계보다 먼저 닿는 것으로 보인다. 본질적 한계는
  Redis 단일 노드 ~10만 ops/sec, 그보다 한참 위.
  → 2026-05-17 확장 측정으로 가설을 정량 검증함. 아래 §확장 측정 결과 참고.

### 확장 측정 결과 (2026-05-17)

`RedisStockDeductionBenchmarkTest` 를 `-Dbench.thread.levels=30,100,300,500,1000` ×
`-Dspring.data.redis.lettuce.pool.max-active={32,64,128}` 으로 9개 조합 측정.

#### V4-Redis 처리량(ops/sec) — pool 크기 × thread 수

| Threads | Pool 32 | Pool 64 | Pool 128 |
|---:|---:|---:|---:|
| 30 | 12,631 | 10,620 | 11,873 |
| 100 | **20,584** | 19,793 | 16,955 |
| 300 | 18,903 | 22,177 | **23,007** |
| 500 | **23,389** | 22,752 | 22,756 |
| 1000 | **30,570** | 27,411 | 27,999 |

#### V4-Redis P99 레이턴시(ms) — pool 32 기준

| Threads | P99 |
|---:|---:|
| 30 | 3.5 |
| 100 | 6.1 |
| 300 | 19.2 |
| 500 | 29.2 |
| 1000 | 42.3 |

#### V1/V3 — pool 32 확장

```
V1-Pessimistic         30       353 ops/s   P99    102 ms
V1-Pessimistic        100       500 ops/s   P99    382 ms
V1-Pessimistic        300       564 ops/s   P99  1,818 ms
V1-Pessimistic        500       770 ops/s   P99  2,596 ms
V1-Pessimistic       1000       777 ops/s   P99  5,000 ms  (98.7% success)
V3-CAS                 30     1,026 ops/s   P99    131 ms
V3-CAS                100       958 ops/s   P99    409 ms
V3-CAS                300       945 ops/s   P99  1,330 ms
V3-CAS                500       950 ops/s   P99  2,238 ms
V3-CAS               1000       945 ops/s   P99  4,560 ms  (99.7% success)
```

### 검증된 결론

1. **"300 thread dip 은 Lettuce 풀 포화" 가설 — 부분 입증**: pool 32 → 64 로 키우면
   300 thread 처리량이 18,903 → 22,177 (+17%) 로 복구. pool 128 에서도 23,007 로 유사.
   즉 dip 의 일부는 풀 포화에 기인.
2. **반전 — 풀이 항상 클수록 좋지는 않음**: 1000 thread 에서 pool 32 가 30,570 ops/sec
   로 pool 64/128 의 27,411/27,999 보다 빠르고, 100 thread 에서도 pool 32 가 최고
   (20,584 vs 16,955@pool128). 큰 풀의 컨텍스트 스위칭/큐 오버헤드 또는 작은 풀의
   자연스러운 backpressure 효과로 추정.
3. **본질적 한계는 여전히 멀다**: V4 가 1000 thread / pool 32 에서 **30,570 ops/sec**
   를 달성하며 V3-CAS 의 30배 이상. Redis 단일 노드 천장(~10만 ops/sec) 까지 아직 여유.
4. **V1 의 fairness 손실**: pool 32 / 1000 thread 에서 V1 success rate 가 98.7% 로
   첫 실패 발생 — lock_timeout=5s 초과. V3 도 1000 thread 에서 99.7% (lock_timeout 동일
   원인). V4 는 1000 thread 에서도 100% 성공.

### 운영 시사점

- **단일 pool 크기 권장 불가**: 워크로드 동시성 분포(저/중/고) 에 따라 최적 pool 크기가 다르다.
  본 프로젝트의 burst 시나리오(>=500 thread) 에서는 **pool 32** 가 가장 빠른 선택.
- **dip 은 측정 노이즈 + 풀 매칭 비최적의 결합**: pool 32 의 300 thread 영역만 두드러지는
  국소 현상. 1000 thread 까지 가면 처리량 곡선이 다시 상승해 "fundamental ceiling" 신호는 없음.

### 1억 시나리오에 대한 답 (수정 없음, 강화)

위 한계 조건 섹션의 결론은 그대로다 — burst 일상 경로는 도메인 분리(현재는 `flashsale`),
일반 주문은 비관적 락 유지. **단지, 만약 이 결정을 뒤집어야 할 만큼 burst 가 일상화
된다면**, 그때의 후속 단계가 v2 의 Redis 백엔드 도입임을 본 데이터가 입증한다. 운영
도입에는 (a) DB 양방향 동기 outbox, (b) Redis 장애 폴백, (c) 분산 환경에서 키 sharding
정책이 추가로 필요하며, 이는 본 ADR 의 범위를 넘어선 별도 결정이다.

## 구현 메모(Implementation Notes)

- 벤치마크 전략 구현: `com.shop.domain.order.service.stock.V1PessimisticLockStockDeduction`, `V2OptimisticRetryStockDeduction`, `V3CasUpdateStockDeduction`
- 벤치마크 테스트:
  - `StockDeductionBenchmarkTest` — Low(10t/50p) / High(30t/1p) × V1/V2/V3, 워밍업 1회 + 측정 3회 중앙값
  - `StockDeductionBurstBenchmarkTest` — 1상품 × {30, 100, 300} 스레드, V1/V3 비교, lock-timeout/pool-timeout 분류 (한계 조건 섹션 근거)
  - `RedisStockDeductionBenchmarkTest` — 위 워크로드에 V4-Redis 추가, Testcontainers redis:7-alpine 사용 (v2 실험 섹션 근거)
- v2 실험 컴포넌트(Redis 백엔드, 격리 활성화):
  - `V4RedisStockDeduction` — `StockDeductionStrategy` 의 Redis Lua CAS 구현
  - `RedisOrderStockProcessor` + `Config` — 일반 주문 swap (`shop.backend=redis`)
  - `RedisFlashSaleCommandService` + `Config` — 플래시 세일 swap (`flash-sale.backend=redis`)
  - `global.redis.{RedisConfig, StockKeyResolver, StockPreloader}` — 인프라 빈 (@Profile("redis"))
- 프로덕션 경로(`OrderStockProcessor`)는 변경하지 않았다. 전략 컴포넌트는 벤치마크 전용이다.

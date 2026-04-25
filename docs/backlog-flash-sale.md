# 백로그: 플래시 세일(타임세일) 기능 설계

대상 독자: 설계 리뷰어, 구현 담당자, 동시성/부하테스트 담당자
상태: 제안(Proposed) — 구현 이전 설계 합의 문서
관련 코드: `ProductRepository.decreaseStockAtomic`, `OrderStockProcessor`, `IdempotencyExecutor`, `RateLimitFilter`, `CacheConfig`
관련 문서: [`analysis-execution-plan-optimization.md`](./analysis-execution-plan-optimization.md) §1-2, [`order-invariant-checks.md`](./order-invariant-checks.md), [`load-test-benchmark.md`](./load-test-benchmark.md) §10-7

---

## 목차

1. [배경 & 목적](#1-배경--목적)
2. [요구사항](#2-요구사항)
3. [도메인 모델 & 스키마](#3-도메인-모델--스키마)
4. [동시성 아키텍처 — 5계층 설계](#4-동시성-아키텍처--5계층-설계)
5. [핵심 알고리즘 — 재고 예약/차감](#5-핵심-알고리즘--재고-예약차감)
6. [캐시 전략](#6-캐시-전략)
7. [API 설계](#7-api-설계)
8. [실패 모드 & 보상(Saga)](#8-실패-모드--보상saga)
9. [측정 & 검증 계획](#9-측정--검증-계획)
10. [대안 설계 비교 — 왜 이 구조인가](#10-대안-설계-비교--왜-이-구조인가)
11. [마이그레이션 & 운영 적용](#11-마이그레이션--운영-적용)
12. [Phase 분할 구현 계획](#12-phase-분할-구현-계획)
13. [참고 & 오픈 이슈](#13-참고--오픈-이슈)

---

## 1. 배경 & 목적

### 1-1. 왜 플래시 세일인가

현재 시스템은 일반 주문 경로에서 동시성 통제 기법을 이미 여러 계층에 갖추고 있다(비관적 락, CAS 원자 UPDATE, OSIV off, 가상 스레드, 멱등성, Rate Limit, Outbox). 그러나 이 기법들은 **"동일 상품에 대한 1초 내 수백~수천 건의 burst"** 라는 플래시 세일 고유의 부하 패턴에서 검증된 적이 없다.

일반 주문과 플래시 세일이 다른 점:

| 축 | 일반 주문 | 플래시 세일 |
|---|---|---|
| 대상 상품 수 | 카트 N개 (분산) | 1개에 집중 |
| 시간당 부하 | 평탄 | 시작 시각 ±5초에 극단 peak |
| 오버셀 허용도 | 실사용에선 완화 가능 | **0건 (비즈니스 계약)** |
| 공정성(fairness) | 미요구 | 요구 — "먼저 누른 사람이 먼저 받아야" |
| 읽기 경로 | 카트/상세 분산 | 단일 이벤트 페이지에 집중 — 캐시 thundering herd 위험 |

즉 플래시 세일은 기존 기법들의 **상한(조합·상호작용·실패 모드)** 을 실측으로 드러내는 문제다. 본 기능의 구현 목표는 매출이 아니라 **"프로젝트가 내세우는 동시성 통제가 실제 peak 부하에서 정량적으로 통한다는 증거"** 를 남기는 것이다.

### 1-2. 성공 기준 (SLO)

| 지표 | 목표 | 근거 |
|---|---|---|
| 오버셀(over-sell) 발생 건수 | **0건** | 재고 N·요청 N×10 burst에서 정확히 N개만 SUCCESS |
| 성공 응답 p95 | **< 120ms** | 기존 `POST /orders` p95 13.5ms의 약 10배 이내 (§10-5-5 기준) |
| 실패 응답(sold-out) p95 | **< 50ms** | 단일 CAS UPDATE 1회 경로 |
| 재고 정합성 | **DB.initial − sum(orders) = DB.final, 항상 성립** | 자동 invariant 체크 |
| Rate limit 부작용 | 정상 사용자 차단 0건 | FLASH_SALE 플랜 한도 산정은 §7-3 |
| 동시 VU 1,000에서 처리량 | ≥ 500 req/s | 가상 스레드 ON + HikariCP 17 기준, burst 모양 측정 |

### 1-3. 범위 제한

MVP에 포함하지 않는 것:
- 어드민 CRUD UI (세일 생성은 DB에 직접 INSERT 또는 테스트 시드로 주입).
- 결제 게이트웨이 연동 (기존 주문처럼 포인트/쿠폰 결제만 지원).
- 가상 대기열(Virtual Waiting Room) — 별도 기능으로 분리(§13 오픈 이슈).
- 실시간 재고 푸시(SSE/WebSocket) — 1초 polling + 캐시로 대체.

---

## 2. 요구사항

### 2-1. 기능 요구사항

**F1.** 관리자가 `FlashSale` 이벤트를 생성할 수 있다(start_time, end_time, 대상 상품, 할당 수량, 세일 가격).
**F2.** 사용자는 진행 중인 세일 목록을 조회할 수 있다(`GET /flash-sales`).
**F3.** 사용자는 세일 상세 + 현재 남은 재고(근사치)를 조회할 수 있다(`GET /flash-sales/{id}`).
**F4.** 사용자는 세일 시작 시각부터 종료 시각 사이에만 구매 요청을 보낼 수 있다(`POST /flash-sales/{id}/purchase`).
**F5.** 동일 사용자는 동일 세일에서 **1회만** 구매할 수 있다.
**F6.** 세일 상품 구매는 정상 주문(`orders`/`order_items`) 레코드로 귀결되어 기존 주문 조회/취소 경로가 그대로 동작한다.
**F7.** 세일 재고가 소진되면 이후 요청은 **409 Conflict**(`sold_out`)를 받는다.

### 2-2. 비기능 요구사항

- **N1 오버셀 금지**: 재고 100개 세일에 10,000명이 동시 진입해도 성공 건수 = 100.
- **N2 공정성**: FIFO를 강하게 보장하지 않는다(분산 환경에서 낭비). 대신 "성공/실패 판정은 서버 수신 시점에 따라 결정된다" 수준의 약한 선착순을 제공.
- **N3 원자성**: 재고 차감과 주문 생성은 단일 트랜잭션 내에서 원자적이어야 한다. 부분 실패 시 재고 복구.
- **N4 멱등성**: 네트워크 재시도로 인한 이중 구매 금지. `X-Idempotency-Key` 헤더 필수.
- **N5 관측성**: 세일별 요청/성공/실패/오버셀 시도 카운트를 로그와 메트릭으로 노출.

### 2-3. 불변식(Invariant)

| 불변식 | 검증 위치 |
|---|---|
| `flash_sale_items.remaining_quantity >= 0` | CHECK 제약 + CAS WHERE 절 |
| `flash_sale_items.allocated_quantity >= sum(successful orders.quantity)` | 주기적 배치 또는 관리자 리포트 |
| `orders.total_amount == flash_sale_items.sale_price * quantity` (세일 주문) | `OrderInvariantValidator` 확장 |
| 동일 `(user_id, flash_sale_id)` 성공 주문 수 ≤ 1 | UNIQUE 인덱스 |

---

## 3. 도메인 모델 & 스키마

### 3-1. 엔티티 구성

새 도메인 패키지: `com.shop.domain.flashsale`.

```
flashsale/
├── controller/
│   ├── FlashSaleViewController.java    # SSR: 이벤트 페이지
│   └── api/FlashSaleApiController.java # REST: 조회/구매
├── dto/
├── entity/
│   ├── FlashSale.java
│   ├── FlashSaleItem.java
│   └── FlashSaleStatus.java  # SCHEDULED|ACTIVE|ENDED|CANCELLED
├── repository/
│   ├── FlashSaleRepository.java
│   └── FlashSaleItemRepository.java
└── service/
    ├── FlashSaleQueryService.java        # @Cacheable 읽기
    ├── FlashSaleReservationService.java  # CAS 예약
    └── FlashSalePurchaseService.java     # Facade — 예약+주문 오케스트레이션
```

### 3-2. 테이블 설계

```sql
CREATE TABLE flash_sales (
    flash_sale_id   BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    start_time      TIMESTAMP    NOT NULL,
    end_time        TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         INT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_flash_sale_status
        CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','CANCELLED')),
    CONSTRAINT chk_flash_sale_time
        CHECK (end_time > start_time)
);

CREATE TABLE flash_sale_items (
    flash_sale_item_id   BIGSERIAL PRIMARY KEY,
    flash_sale_id        BIGINT        NOT NULL,
    product_id           BIGINT        NOT NULL,
    sale_price           DECIMAL(12,2) NOT NULL,
    allocated_quantity   INT           NOT NULL,  -- 세일 시작 시 할당량
    remaining_quantity   INT           NOT NULL,  -- CAS 대상
    per_user_limit       INT           NOT NULL DEFAULT 1,

    CONSTRAINT fk_fsi_flash_sale FOREIGN KEY (flash_sale_id)
        REFERENCES flash_sales(flash_sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_fsi_product FOREIGN KEY (product_id)
        REFERENCES products(product_id),
    CONSTRAINT chk_fsi_remaining CHECK (remaining_quantity >= 0),
    CONSTRAINT chk_fsi_allocated CHECK (allocated_quantity > 0),
    CONSTRAINT chk_fsi_price CHECK (sale_price >= 0),
    CONSTRAINT uk_fsi_sale_product UNIQUE (flash_sale_id, product_id)
);

CREATE TABLE flash_sale_purchases (
    flash_sale_purchase_id BIGSERIAL PRIMARY KEY,
    flash_sale_id          BIGINT   NOT NULL,
    user_id                BIGINT   NOT NULL,
    order_id               BIGINT   NOT NULL,
    purchased_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fsp_flash_sale FOREIGN KEY (flash_sale_id)
        REFERENCES flash_sales(flash_sale_id),
    CONSTRAINT fk_fsp_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_fsp_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT uk_fsp_user_sale UNIQUE (flash_sale_id, user_id)  -- 1인 1구매
);

CREATE INDEX idx_flash_sale_status_start  ON flash_sales(status, start_time);
CREATE INDEX idx_fsi_flash_sale           ON flash_sale_items(flash_sale_id);
CREATE INDEX idx_fsp_flash_sale           ON flash_sale_purchases(flash_sale_id, purchased_at DESC);
```

설계 포인트:

- `remaining_quantity`를 **CAS 대상**으로 분리. `allocated_quantity`는 불변(통계용), `remaining`만 원자적 감분. `products.stock_quantity`와는 분리(§5-2 참조).
- `uk_fsp_user_sale`로 **1인 1구매 제약을 DB 레벨에 고정**. 애플리케이션 레벨 체크(§5-4) 우회를 허용하지 않는다.
- `status` 전이는 스케줄러(`FlashSaleStatusScheduler`)가 1초 주기로 수행. `SCHEDULED → ACTIVE(start_time 도달)`, `ACTIVE → ENDED(end_time 도달 또는 sold_out)`.
- 세일 취소 시 `status=CANCELLED` + 관련 주문 취소는 기존 `OrderCancellationService` 경로 재사용.

### 3-3. 마이그레이션

- `V23__add_flash_sale_tables.sql` — 위 DDL + 인덱스.
- `schema.sql` 동시 갱신 (인덱스 드리프트 재발 방지, [`analysis-execution-plan-optimization.md`](./analysis-execution-plan-optimization.md) §6 원칙).
- `load-test/restore-indexes-c2.sql` / `drop-indexes-c2.sql` 업데이트 필수 (C2 조건 오염 방지).

---

## 4. 동시성 아키텍처 — 5계층 설계

burst 부하 1,000 VU / 1초 구간을 다음 5계층으로 흡수한다. 바깥 계층일수록 **값싼 거절**을, 안쪽으로 갈수록 **비싼 확정**을 수행한다.

```
┌─────────────────────────────────────────────────────────────┐
│ L1. RateLimitFilter (FLASH_SALE 플랜: 분당 3회/사용자)     │ ← in-memory token bucket
├─────────────────────────────────────────────────────────────┤
│ L2. Admission (캐시된 status/남은재고 1차 판정)             │ ← Caffeine 200ms TTL
│       SCHEDULED → 400, ENDED → 400, sold_out(근사) → 409    │
├─────────────────────────────────────────────────────────────┤
│ L3. Idempotency (X-Idempotency-Key 재실행 단축)             │ ← IdempotencyExecutor
│       동일 key 성공 응답 → 캐시 응답 그대로 반환            │
├─────────────────────────────────────────────────────────────┤
│ L4. CAS 원자 예약 (remaining_quantity 감분)                 │ ← 단일 UPDATE, no lock
│       0 반환 → 409 sold_out (확정)                           │
├─────────────────────────────────────────────────────────────┤
│ L5. 주문 생성 + flash_sale_purchases INSERT                  │ ← 기존 OrderCreationService
│       UNIQUE 제약 위배 → 1인 1구매 위반 확정                 │
└─────────────────────────────────────────────────────────────┘
```

### 4-1. 각 계층의 거절 비용

| 계층 | 거절 시 실행 비용 | 거절 단계에서 차단되는 비율(예상) |
|---|---|---|
| L1 RateLimit | O(1) in-memory CAS | 봇/반복 클릭 30~50% |
| L2 Admission | L1 캐시 hit (0.1ms) | sold-out 이후 95%+ |
| L3 Idempotency | DB 1회 SELECT | 재시도 10~20% |
| L4 CAS | DB 1회 UPDATE | 재고 초과 분 |
| L5 주문 생성 | DB 5~10회 (주문/아이템/사용자쿠폰/포인트/아웃박스) | 정상 경로 |

가장 비싼 L5에 도달하는 요청 수를 **재고 수 × α(여유 1.2배 정도)** 로 수렴시키는 것이 설계 목표.

### 4-2. 기존 기법과의 차별점

- **비관적 락을 쓰지 않는다.** 기존 일반 주문의 `findAllByIdInWithLock`은 카트 N개 상품을 안전하게 잠그기 위한 것인데, 플래시 세일은 **상품 1개에 수천 트랜잭션이 경합**한다. `SELECT FOR UPDATE`로 대기 큐를 만들면 HikariCP 17 풀이 즉시 포화한다(대기 스레드가 5초 `lock_timeout` 동안 커넥션 점유). 대신 **CAS 1회 UPDATE**로 확정·실패를 스레드 대기 없이 즉답.
- **읽기는 DB를 거의 건드리지 않는다.** 세일 상세 페이지는 1초 내 수천 건 조회인데, 이를 DB에 흘리면 `idx_flash_sale_status_start`가 버텨도 TPS 상한이 문제. §6 캐시 전략으로 흡수.

---

## 5. 핵심 알고리즘 — 재고 예약/차감

### 5-1. CAS 원자 감분 쿼리

```java
@Modifying
@Query("UPDATE FlashSaleItem f SET f.remainingQuantity = f.remainingQuantity - :qty, "
     + "f.version = f.version + 1 "
     + "WHERE f.flashSaleItemId = :id AND f.remainingQuantity >= :qty")
int reserveAtomic(@Param("id") Long id, @Param("qty") int qty);
```

- 반환값 0 = `sold_out` (WHERE의 `remaining >= qty`가 거짓).
- 반환값 1 = 예약 성공. 이 시점부터 해당 수량은 **배타적으로 소유**됨.
- `@Version`은 수동 증가(기존 `ProductRepository.decreaseStockAtomic` 컨벤션 준수).

### 5-2. `flash_sale_items.remaining` vs `products.stock_quantity` 분리 이유

**결정: 분리한다.** `flash_sale_items.remaining`이 세일용 할당량 전용, `products.stock_quantity`는 기존 일반 재고.

| 쟁점 | 합치기 | 분리하기(선택) |
|---|---|---|
| 단일 진실 | ✅ | ❌ (세일 종료 시 정산 필요) |
| 일반 재고가 세일 중 소진되는 혼선 | ❌ 큰 위험 | ✅ 격리 |
| 기존 CAS(`decreaseStockAtomic`) 재사용 | ✅ 그대로 | ❌ 별도 구현 |
| burst 동시성 경합 | 일반 주문과 동일 row 경합 | 세일 row에만 집중 |
| 사후 정산 | 불필요 | 필요 (세일 종료 시 `products.stock_quantity -= allocated - remaining` 보정) |

**분리 선택 근거**: 세일이 일반 재고와 동일 row를 경합하면 **세일 burst가 일반 주문 경로까지 락/지연시킨다**. 격리가 프로젝트 동시성 목표와 부합. 정산 비용은 세일 종료 시각에 1회 배치로 충분.

### 5-3. 예약 → 주문 생성 → 보상(Saga)

```java
@Transactional
PurchaseResult purchase(Long flashSaleId, Long userId, String idempotencyKey) {
    return idempotencyExecutor.execute(idempotencyKey, () -> {
        FlashSaleItem item = itemRepo.findByFlashSaleIdForRead(flashSaleId); // 캐시 or DB
        assertActive(item.getFlashSale());                                   // L2 확정 재검

        int reserved = itemRepo.reserveAtomic(item.getId(), 1);              // L4
        if (reserved == 0) throw new SoldOutException();

        try {
            Order order = orderCreationService.createFromFlashSale(          // L5
                    userId, item, SALE_PRICE, /* usePoints */ 0);
            purchaseRepo.save(new FlashSalePurchase(flashSaleId, userId, order));
            return PurchaseResult.of(order);
        } catch (DuplicateFlashSalePurchaseException e) {                     // UNIQUE 위배
            itemRepo.restoreAtomic(item.getId(), 1);                         // 보상
            throw e;                                                         // 409 one_per_user
        } catch (RuntimeException e) {
            itemRepo.restoreAtomic(item.getId(), 1);                         // 보상
            throw e;
        }
    });
}
```

**보상 원자성**: 단일 `@Transactional` 트랜잭션이라 `restoreAtomic`은 불필요(롤백이 충분). 다만 **트랜잭션 경계 밖에서 예약이 커밋된 구조**로 확장될 경우(예: 결제 게이트웨이 외부 호출) 명시적 보상이 필수. MVP는 단일 트랜잭션으로 끝내고, 외부 결제 연동 시 보상 경로를 별도 Phase로 분리.

### 5-4. 1인 1구매 다중 방어

| 계층 | 메커니즘 | 막히는 케이스 |
|---|---|---|
| L3 | Idempotency Key | 동일 요청 재시도 |
| 애플리케이션 | `purchaseRepo.existsByUserIdAndFlashSaleId` 사전 체크 | 다른 키로 재요청(빠른 거절) |
| **L5 DB** | `uk_fsp_user_sale` UNIQUE | 동시 두 요청이 둘 다 사전 체크 통과한 race |

DB UNIQUE가 **최종 방어선**이며, 상위 두 계층은 성능 최적화에 불과하다. UNIQUE 위배(PSQL SQLState `23505`)를 `DuplicateFlashSalePurchaseException`으로 매핑.

### 5-5. 왜 `@Lock(PESSIMISTIC_WRITE)`가 아닌가 — 벤치 기반 근거

기존 `ProductRepository`는 세 변종을 모두 가지고 있다: 비관적(`findAllByIdInWithLock`), 낙관적(`findAllByIdInOrderByProductId`), CAS(`decreaseStockAtomic`). 본 설계는 CAS만 쓴다. 이유:

- 비관적 락은 같은 row에 대기하는 스레드가 `lock_timeout=5s` 동안 HikariCP 커넥션을 점유. 1,000 VU burst에서는 커넥션 풀(17) 즉시 포화 → 주문 외 모든 경로(읽기, 장바구니 등)가 연쇄 대기.
- 낙관적 락은 `@Version` 충돌 시 `OptimisticLockException` 발생 후 재시도 권장인데, 플래시 세일에서는 **재시도가 선착순 공정성을 역전**시킨다(느린 첫 시도자가 재시도로 먼저 획득 가능).
- CAS 1회 UPDATE는 PostgreSQL Row Lock을 **UPDATE 실행 시간(수 ms) 동안만** 보유. 커넥션 점유 시간이 가장 짧고, 실패 판정이 결정적(0 반환).

이 비교는 Phase 23-4(§12)에서 세 변종을 동일 부하로 측정해 **실측 수치와 함께 결론을 강화**한다(`load-test-benchmark.md`에 편입).

---

## 6. 캐시 전략

### 6-1. 신규 Caffeine 캐시 2종

`CacheConfig`에 추가:

```java
cacheSeconds("flashSaleActiveList", 1, 10),   // 진행중 세일 목록 (TTL 1s)
cacheMillis("flashSaleMeta", 200, 500),       // 세일 메타 (status/sale_price/end_time, TTL 200ms)
```

- `flashSaleActiveList`: `GET /flash-sales` 응답. 초당 수천 건 조회 → DB 1회/초로 수렴.
- `flashSaleMeta`: 상세 페이지 + L2 Admission 사전 판정. TTL 200ms로 **"시작/종료 전환 감지 지연"을 200ms 이내로 고정**. 상세 페이지의 "남은 수량"은 근사치이며 정확한 판정은 L4에서 수행되므로 수량 부정확 허용.
- `sync=true`로 cache-miss storm 방지(Phase 21 `productListCount` 설계 재적용).

### 6-2. 남은 수량을 캐시하지 않는 이유

`remaining_quantity` 자체는 **절대 캐시하지 않는다**. 이유:

- 캐시된 수량 기준으로 L2에서 통과시킨 뒤 L4 CAS가 0을 반환하면 사용자는 "남은 수량 15개로 보였는데 sold_out"을 경험 → UX 위배.
- L4 CAS 결과가 유일한 진실. 남은 수량 표시는 `flashSaleMeta`의 `remaining_approx`로 별도 제공(1~5초 지연 허용).

### 6-3. 캐시 무효화 이벤트

| 트리거 | 무효화 대상 |
|---|---|
| `FlashSaleStatusScheduler`가 status 전환 | `flashSaleActiveList`, `flashSaleMeta:{id}` |
| 관리자가 세일 취소 | `flashSaleActiveList`, `flashSaleMeta:{id}` |
| `remaining_quantity = 0` 도달(sold_out) | `flashSaleMeta:{id}` (TTL에 맡겨도 OK) |

무효화는 `ProductCacheEvictHelper`와 동일 패턴의 `FlashSaleCacheEvictHelper`로 구현.

---

## 7. API 설계

### 7-1. 엔드포인트

| 메서드 | 경로 | 설명 | 인증 | Rate Limit |
|---|---|---|---|---|
| GET | `/api/flash-sales` | 진행중/예정 세일 목록 | 선택 | READ |
| GET | `/api/flash-sales/{id}` | 세일 상세 | 선택 | READ |
| POST | `/api/flash-sales/{id}/purchase` | 즉시 구매 | 필수 | **FLASH_SALE(신규)** |
| GET | `/flash-sales` (SSR) | 이벤트 페이지 | 선택 | READ |
| GET | `/flash-sales/{id}` (SSR) | 상세 페이지 | 선택 | READ |

### 7-2. 구매 요청/응답 스펙

**요청**
```http
POST /api/flash-sales/42/purchase
X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json
Authorization: Bearer <JWT>

{ "quantity": 1 }
```

**성공(201)**
```json
{ "orderId": 90123, "orderNumber": "2026-04-24-0090123",
  "salePrice": "19900", "quantity": 1, "totalAmount": "19900" }
```

**실패 케이스**
| HTTP | code | 의미 |
|---|---|---|
| 400 | `not_started` | `now < start_time` |
| 400 | `ended` | `now >= end_time` 또는 `status=ENDED` |
| 409 | `sold_out` | L4 CAS 0 반환 |
| 409 | `one_per_user` | UNIQUE 위배 |
| 429 | `rate_limit_exceeded` | L1 토큰 고갈 |
| 400 | `idempotency_key_missing` | 헤더 누락 |

### 7-3. `RateLimitPlan.FLASH_SALE` 산정

```java
FLASH_SALE(3, 3, 60_000),  // capacity 3, refill 3/60s
```

- 정상 사용자는 세일 1건당 1번 누른다. 재시도를 포함해도 3회면 충분.
- ORDER(5/60s)보다 엄격. 봇/매크로의 "같은 세일 반복 공격"을 막기 위함.
- 키: `사용자ID + endpoint`로 고정(IP 기반은 사내망/모바일 캐리어 공유 NAT에서 오탐 위험).

---

## 8. 실패 모드 & 보상(Saga)

### 8-1. 실패 매트릭스

| 실패 지점 | 감지 방법 | 처리 |
|---|---|---|
| DB 커넥션 고갈 | HikariCP timeout | 500 + 서킷 브레이커 후보(§13) |
| CAS 0 반환 | `int == 0` | 409 `sold_out`, 캐시 evict |
| UNIQUE 위배 (1인 1구매) | `DataIntegrityViolation` SQLState 23505 | `restoreAtomic(1)` + 409 `one_per_user` |
| UNIQUE 위배 (order_number 중복) | 동일 | 주문 생성 경로 재시도 또는 롤백 |
| 포인트/쿠폰 사용 실패 | 기존 예외 전파 | 트랜잭션 롤백(CAS 자동 복구) |
| 애플리케이션 크래시(예약 후) | — | 트랜잭션 미커밋 = 예약도 미커밋(분리 TX 아님) |
| 외부 결제 실패(향후) | 결제 응답 | 별도 보상 트랜잭션으로 `restoreAtomic` |

### 8-2. 핵심 원칙 — "트랜잭션 경계와 예약 경계를 일치"

§5-3 구현이 단일 `@Transactional`로 묶여 있으므로 **예약은 오직 주문 INSERT와 함께 커밋**된다. 따라서 "예약은 됐는데 주문이 없음" 상태가 존재하지 않는다. 이게 MVP의 보상 부담을 제거하는 단일 가장 중요한 설계 결정.

### 8-3. 배치 정합성 검사

`FlashSaleReconciliationJob`(Phase 23-3에서 선택적 구현) — 세일 종료 후 10분 내 1회 실행:

```sql
SELECT f.flash_sale_item_id,
       f.allocated_quantity - f.remaining_quantity AS sold,
       COALESCE(o.total_sold, 0)                    AS ordered
FROM flash_sale_items f
LEFT JOIN (
    SELECT fsp.flash_sale_id, SUM(oi.quantity) total_sold
    FROM flash_sale_purchases fsp
    JOIN order_items oi ON oi.order_id = fsp.order_id
    GROUP BY fsp.flash_sale_id
) o ON o.flash_sale_id = f.flash_sale_id
WHERE (f.allocated_quantity - f.remaining_quantity) <> COALESCE(o.total_sold, 0);
```

0행이면 정합성 OK. 불일치 발생 시 알람 + 상세 조사(MVP는 로그 기록만).

---

## 9. 측정 & 검증 계획

### 9-1. 단위 테스트

- `FlashSaleReservationServiceTest#reserve_zeroWhenSoldOut` — CAS 0 반환 케이스.
- `FlashSalePurchaseServiceTest#enforceOnePerUserViaDbUnique` — 애플리케이션 사전체크 우회 시 UNIQUE 위배 매핑.
- `FlashSalePurchaseServiceTest#restoresRemainingWhenOrderCreationFails` — 내부 보상.
- 기존 `OrderInvariantValidator`에 `flash_sale_price * quantity == total_amount` 규칙 추가 후 해당 케이스 검증.

### 9-2. 동시성 통합 테스트

`FlashSaleConcurrencyIT` (`@SpringBootTest` + `ExecutorService` 30 스레드):
- 초기 재고 100 → 300개 동시 요청 → 정확히 100개 SUCCESS, 200개 409.
- 동일 사용자 10 요청 동시 → 정확히 1개 SUCCESS, 9개 409.
- 이후 `SELECT remaining_quantity` = 0 & `SELECT COUNT(*) FROM flash_sale_purchases` = 100 검증.

### 9-3. k6 burst 시나리오

`load-test/flash-sale-benchmark.sh` 신규 작성. `storm-benchmark.sh` 구조 재사용:

```bash
# 1. 세일 생성 (allocated=100, 시작 시각 = now+10s)
# 2. 앱 재시작 → cold cache
# 3. k6 run: 1,000 VU가 시작 시각 정확히 맞춰 POST
# 4. 결과 수집:
#    - success_count (HTTP 201)
#    - sold_out_count (409 sold_out)
#    - p95/p99 latency
#    - DB 검증: remaining_quantity == 0 && COUNT(purchases) == 100
```

비교 대상: CAS vs 비관적 락 변종(§5-5). 동일 부하에서 p95·처리량·sold_out 반환 latency를 표로 기록.

### 9-4. 관측 지표

- 구조적 로그 이벤트(신규):
  - `event=flash_sale_reserve_success sale_id=... remaining_after=...`
  - `event=flash_sale_reserve_soldout sale_id=... user_id=...`
  - `event=flash_sale_purchase_duplicate sale_id=... user_id=...`
- MDC: `flashSaleId`, `idempotencyKey` 바인딩.
- 메트릭(Caffeine stats 패턴 재사용): `flashSaleActiveList.hit_rate`, `flashSaleMeta.hit_rate`.

---

## 10. 대안 설계 비교 — 왜 이 구조인가

### 10-1. 재고 관리 위치

| 옵션 | 설명 | 채택 | 기각 이유 |
|---|---|---|---|
| A | `products.stock_quantity` 직접 감분 | ❌ | 일반 주문 경로와 경합, burst가 카트 경로를 막음 |
| B | **`flash_sale_items.remaining_quantity` 분리 + CAS** | ✅ | 격리·정산 가능·기존 CAS 패턴 재사용 |
| C | Redis INCR 기반 원자 감분 | ❌ | 프로젝트가 Redis 미도입(의존성 신규 추가 비용) |

### 10-2. Admission 제어

| 옵션 | 설명 | 채택 | 기각 이유 |
|---|---|---|---|
| A | **즉시 처리 (본 설계)** | ✅ MVP | 구현 단순, p95 측정 용이 |
| B | 가상 대기열(Virtual Waiting Room) + 입장권 토큰 | ❌(차후) | SSE/폴링 인프라 + 입장권 TTL 등 범위 폭증 |
| C | FIFO 큐(Kafka-like) | ❌ | 모놀리스에서 과잉, 메시징 인프라 부재 |

§13 오픈 이슈에 "가상 대기열 차후 phase" 기록.

### 10-3. 1인 1구매 보증

| 옵션 | 채택 | 설명 |
|---|---|---|
| DB UNIQUE 제약 | ✅ | 유일한 **강한** 보증 |
| Redis SETNX | ❌ | Redis 미도입 |
| 분산 락 | ❌ | 오버엔지니어링, 모놀리스 단일 인스턴스 가정 |

### 10-4. 캐시 TTL 선택

| 캐시 | TTL | 대안 | 채택 이유 |
|---|---|---|---|
| `flashSaleActiveList` | 1s | 5s | 시작/종료 전환 가시 지연을 1초로 제한 |
| `flashSaleMeta` | 200ms | 1s | Admission 판정 정확도(너무 긴 TTL은 sold_out 이후 L2 통과가 늘어 L4 부하 증가) |

---

## 11. 마이그레이션 & 운영 적용

### 11-1. 배포 순서

1. V23 마이그레이션 적용 (`psql -f migration/V23__add_flash_sale_tables.sql`). DDL만이라 CONCURRENTLY 불필요(빈 테이블 생성).
2. `schema.sql`·C2 drop/restore 스크립트 동기화 (§3-3).
3. 애플리케이션 배포.
4. 관리자가 `flash_sales`·`flash_sale_items` 시드(MVP는 DB 직접 INSERT, 또는 `/admin/flash-sales` 엔드포인트를 Phase 23-5로 분리).
5. `FlashSaleStatusScheduler` 1초 폴링 확인(로그).

### 11-2. 운영 플레이북 (세일 당일)

```
T-10min  상품/세일 시드 검증, /api/flash-sales에서 목록 확인
T-5min   Caffeine 워밍업(curl 1회 — §10-7 패턴)
T-1min   DB 커넥션/CPU 모니터 확인
T-0      세일 시작 — 앱 로그로 status 전환 확인
T+1min   성공/실패/오버셀 카운트 집계 (§9-4 로그 grep)
T+30min  FlashSaleReconciliationJob 수동 실행 (§8-3)
```

### 11-3. 운영 실패 시 긴급 종료

`UPDATE flash_sales SET status='CANCELLED' WHERE flash_sale_id=...;` 1회 + 캐시 무효화. 이후 구매 요청은 L2에서 `ended`로 판정(status != ACTIVE).

---

## 12. Phase 분할 구현 계획

| Phase | 범위 | 예상 소요 | 종료 기준 |
|---|---|---|---|
| 23-1 ✅ | 엔티티·스키마·V23·읽기 API(`GET /flash-sales`, 상세) | 1.5일 | `./gradlew test check` PASS, SSR 페이지 렌더 |
| 23-2 ✅ | 구매 API + CAS 예약 + `FLASH_SALE` RateLimit + Idempotency | 2일 | 단위 테스트 PASS, 수동 POST로 성공/sold_out/one_per_user 재현 |
| 23-3 ✅ | `FlashSaleConcurrencyIT` + 보상 경로 + Reconciliation 쿼리 | 1.5일 | 오버셀 0 검증 테스트 그린, JaCoCo 60% 유지 |
| 23-4 ✅ | k6 burst 시나리오 + CAS vs 비관적 락 벤치 + 문서화 | 1일 | `load-test-benchmark.md` §11 신규 절에 p95·오버셀·처리량 기록 |
| 23-5 (선택) | 어드민 CRUD + 상세 대시보드 | 2일 | out of MVP |

**총 MVP 예상**: 5~6일. Phase 23-5 제외.

### 12-1. 각 Phase의 Definition of Done

공통: 모든 Phase가 `./gradlew check`, `validate-doc-stats.sh`, `check-domain-dependencies.sh` PASS.

- 23-1: `schema.sql`·V23·C2 스크립트 3 파일 정합성 확인.
- 23-2: `RateLimitPlan` 확장 + `RateLimitPlanResolver` 매핑 + `rest-api-guide.md` 갱신.
  - **구현 시 확정된 규약 차이**: 설계안 `/api/v1/flash-sales/{id}/purchase` 대신
    실제 경로는 `/api/v1/flash-sales/{id}/items/{itemId}/purchase` 로 `items/{itemId}` 세그먼트를 추가했다.
    한 세일에 여러 아이템이 달리는 구조(`flash_sale_items`)에서 어느 아이템을 구매하는지 URL이 드러내야 하기 때문.
    수량은 `1` 고정(MVP), per_user_limit은 `uk_fsp_user_sale`로 강제되어 무시.
- 23-3: `OrderInvariantValidator.validateFlashSaleOrder(...)` 추가(쿠폰/포인트/배송비 0, 단일 라인, 금액 정합) +
  `FlashSaleOrderFactory`가 저장 전에 호출 + `order-invariant-checks.md`에 §"플래시 세일 주문 정합성 점검"
  3종 SQL 추가 + `FlashSaleConcurrencyIT` 3종(오버셀 0 / 1인1구매 보상 / 정합성 항등식).
- 23-4: `load-test-benchmark.md`에 §11절(플래시 세일) 신규 추가 — 200 VU burst·재고 100·동일 코드 경로에서
  CAS vs 비관적 락 비교, p95 416ms vs 472ms(+13%), 양 전략 모두 오버셀 0, server_err 0,
  `EntityManager.refresh(PESSIMISTIC_WRITE)`로 1차 캐시 우회 함정 해소까지 기록.
  스크립트(`load-test/flash-sale-burst.js`, `setup-flash-sale.sql`, `reset-flash-sale.sql`)와
  `flash-sale.lock-strategy` 환경변수 스위치로 재현 가능.

---

## 13. 참고 & 오픈 이슈

### 13-1. 참고 코드

| 대상 | 경로 |
|---|---|
| CAS 원자 감분 선례 | `ProductRepository.decreaseStockAtomic` |
| 멱등성 실행 헬퍼 | `IdempotencyExecutor` |
| Rate Limit 토큰 버킷 | `RateLimitPlan`, `TokenBucket`, `RateLimitFilter` |
| 주문 생성 facade | `OrderCreationService` |
| 주문 불변식 | `OrderInvariantValidator` |
| 캐시 evict 헬퍼 선례 | `ProductCacheEvictHelper` |
| 부하 burst 선례 | `load-test/storm-benchmark.sh` |

### 13-2. 오픈 이슈 — 차후 Phase 후보

1. **가상 대기열(Virtual Waiting Room)**: FLASH_SALE 플랜이 L1에서 값싸게 흡수하지 못하는 규모(10k VU+)가 되면 필요. 입장권 TTL·서버 수용량 기반 ramp 설계는 본 문서 범위 밖.
2. **Redis 도입 시 재설계**: 본 설계는 단일 인스턴스 모놀리스 가정. 수평 확장 시 `remaining_quantity` CAS를 Redis INCR로 옮기고 DB는 주문 기록만 담당하는 구조가 자연스러움.
3. **서킷 브레이커**: HikariCP 포화 시 구매 경로만 차단하고 조회 경로는 살리는 로컬 브레이커(Resilience4j)의 필요성 — Phase 23-4 벤치 결과로 판단.
4. **결제 게이트웨이 연동**: 외부 호출이 개입하면 §8-2의 "트랜잭션 경계 == 예약 경계" 전제가 무너진다. 외부 결제는 별도 TX로 분리하고 `restoreAtomic` 보상 + Outbox 이벤트로 재설계 필요.
5. **세일 CRUD 어드민 UI**: Phase 23-5. 현재는 DB 직접 INSERT로 충분.
6. **세일 주문 취소 정책**: 현재 `/orders/{id}` 상세 페이지의 "주문 취소" 버튼은 `orderStatusCode in (PENDING|PAID)` 조건만으로 노출되어 세일 주문에도 표시된다. `OrderCancellationService.cancelOrderInternal`은 `products.stock_quantity`를 복원(증가)하지만 세일 주문은 일반 재고를 차감하지 않았으므로 잘못된 인플레가 발생하고, `flash_sale_items.remaining_quantity`도 복원되지 않는다. Phase 23-5+ 에서 (a) 세일 주문 식별 + 취소 차단 또는 (b) 정상 보상 경로(remaining 증가 + products 인플레 방지)로 분기 처리 필요. 도메인 의존성 규칙(`order ↔ flashsale` 양방향 금지)을 우회하기 위해 이벤트/어댑터 패턴 또는 Order 엔티티에 origin 마커 도입 고려.

### 13-3. 결정해야 할 사항(합의 전)

- **쿠폰 적용 허용 여부**: 세일 가격에 쿠폰 중첩을 허용하면 이익률 통제 이슈. 기본 "허용 안 함"으로 시작하되, 최종 결정은 도메인 오너 판단.
- **포인트 사용 허용 여부**: 동시성과 무관하지만 주문 금액 계산에 영향. 기본 "허용"으로 제안.
- **세일 재고와 일반 재고의 최종 정산 시점**: 세일 종료 직후 vs 일 1회 배치. 배치 쪽이 단순.

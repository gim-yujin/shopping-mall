# 분석: 데이터 정합성·일관성 정적 검토 (2026-05-14)

대상: 주문/재고/쿠폰/포인트/Outbox/캐시 도메인의 데이터 흐름
방법: 호출 그래프 기반 정적 검토 (런타임 검증/재현 없음)
범위 제외: 인증/인가, 입력 검증, 성능 분석, DB 스키마 직접 점검 (`docs/order-invariant-checks.md` 참고)

## 요약

총 10개 항목을 발견했다. 등급별 분포는 High 3건, Medium 4건, Low 3건.

| # | 등급 | 제목 | 영향 |
|---|---|---|---|
| 1 | High | V4 Redis 재고 차감과 외부 트랜잭션 간 dual-write 불일치 | Redis 재고 영구 손실 / 재시도 시 다중 차감 |
| 2 | High | 부분취소 누적으로 전체 CANCELLED 전이 시 outbox 누락 | 취소 알림 영구 유실, 상품 캐시 부분 stale |
| 3 | High | 주문 생성/취소 알림이 두 경로에서 모두 발송 (중복 발송) | 실제 알림 연동 시 사용자에게 2회 이상 알림 |
| 4 | Medium | V3 CAS UPDATE의 inventory history before/after 스냅샷 부정확 | 감사 로그 신뢰성 저하 |
| 5 | Medium | `OrderPostProcessor` PointHistory.balanceAfter 의미 모호 | 회귀 시 동일 사후값 중복 기록 가능 |
| 6 | Medium | `OrderInvariantValidator`가 totalAmount/finalAmount 등식 미검증 | 계산 회귀가 DB CHECK까지 통과 |
| 7 | Medium | `userCouponRepository.findByOrderId`의 단일 결과 가정 | 중복 row 발생 시 취소 자체 차단 |
| 8 | Low | `entityManager.refresh(product)`로 락 보유 시간 증가 | 동시성 처리량 손해 |
| 9 | Low | advisory lock + product lock 간 데드락 잠재성 | 향후 CartService 변경 시 회귀 위험 |
| 10 | Low | Outbox 폴러의 단일 트랜잭션 범위 — 캐시 evict 부수효과 | 향후 외부 부수효과 추가 시 경계 재검토 필요 |

---

## High

### 1. V4 Redis 재고 차감과 외부 트랜잭션 간 dual-write 불일치

**위치**: `src/main/java/com/shop/domain/order/service/RedisOrderStockProcessor.java:59-112`,
`src/main/java/com/shop/domain/order/service/OrderCreationService.java:115-131`

**관찰**: `RedisOrderStockProcessor.deductStockAndBuildOrderLines`는 Lua DECR이 성공하면 그대로 리턴한다. `OrderCreationService.createOrder`는 `@Transactional` 안에서 이 메서드를 호출하지만, Redis 작업은 JTA/Spring 트랜잭션에 참여하지 않는다.

**시나리오**:
1. Redis 재고 차감 성공 (Lua `DECRBY` 반영)
2. 이후 단계에서 예외 발생 — 쿠폰 락 획득 실패, `processPointsUsage` BusinessException, outbox payload 직렬화 실패, DB constraint 위반 등
3. `@Transactional` 롤백 → DB는 깨끗하지만 **Redis 재고는 영구 손실**

**증폭 요인**: `OrderCreationService.createOrder`에는 `@Retry(name = "orderCreation")` + `@CircuitBreaker`가 걸려 있다. `DataAccessException`(retryExceptions에 포함)이 발생하면 createOrder 전체가 재시도되며, 매 시도마다 Redis 차감이 누적된다. maxAttempts=2 기준으로 최악의 경우 정상치의 2배까지 차감이 가능하다.

`rollbackIncrement`는 단일 호출 내 다중 아이템 중 일부 실패에만 반응하며, 외부 트랜잭션 롤백에는 hook이 없다.

**수정 방향**:
- `TransactionSynchronizationManager.registerSynchronization`로 `afterCompletion(STATUS_ROLLED_BACK)` 시 Redis `INCRBY` 보상 등록
- 또는 Redis 차감을 outbox PROCESSED 이후로 옮기고, 차감 전 단계에서는 "예약" 카운터를 사용
- 또는 `@Retry`를 V4 모드에서 무력화하고 멱등성 토큰(orderId 후보)으로 Lua 스크립트를 idempotent하게 변경

---

### 2. 부분취소 누적으로 전체 CANCELLED 전이 시 outbox 누락

**위치**: `src/main/java/com/shop/domain/order/service/PartialCancellationService.java:457-466`
**대조군**: `src/main/java/com/shop/domain/order/service/OrderCancellationService.java:200-205`

**관찰**: `transitionIfFullyCancelled`는 모든 아이템 잔량이 0이 되면 `order.cancel()` + 쿠폰 복원만 수행한다. 그러나 `OrderCancellationService.cancelOrderInternal`이 호출하는 두 가지 outbox 발행이 빠져 있다:
- `outboxEventPublisher.publishOrderCancelled(orderId, userId, remainingRefundAmount)` — 취소 알림 트리거
- `outboxEventPublisher.publishStockChanged(productIds)` — 상품 상세 캐시 무효화

아이템별로는 `applyRefund` 안에서 `publishStockChanged(List.of(item.getProductId()))`가 호출되므로 재고 캐시는 부분적으로 갱신된다. 그러나 ORDER_CANCELLED outbox는 누구도 발행하지 않는다.

**결과**:
- 사용자가 N개 아이템을 1개씩 부분취소로 누적하여 결과적으로 주문 전체가 취소되면, **취소 알림이 영원히 발송되지 않는다**.
- 처음부터 `cancelOrder(orderId)`를 호출했을 경우와 의미적 결과(주문 CANCELLED + 재고 복원 + 환불)는 같지만, 사용자 가시성 측면에서는 다른 상태가 된다.

**수정 방향**: `transitionIfFullyCancelled` 내부에서 `order.cancel()` 직후 `outboxEventPublisher.publishOrderCancelled(...)` 추가. 환불액은 누적된 `order.getRefundedAmount()`를 사용.

---

### 3. 주문 생성/취소 알림이 두 경로에서 모두 발송 (중복 발송)

**위치**:
- 경로 A: `src/main/java/com/shop/domain/order/service/OrderPostProcessingListener.java:91-100`, `137-146`
- 경로 B: `src/main/java/com/shop/global/outbox/handler/OrderCreatedEventHandler.java:65`, `OrderCancelledEventHandler.java:57`

**관찰**: `OutboxEventPublisher`의 헤더 주석(`OutboxEventPublisher.java:74-77`)은 두 경로의 역할 분리를 명시한다:
> "ApplicationEvent(@Async)는 내부 후처리(등급 재계산)를 담당하고, Outbox는 외부 연동(알림 발송)을 담당한다."

그러나 실제 코드에서는 두 경로 모두 `OrderNotificationService.sendOrderConfirmation` / `sendCancellationNotice`를 호출한다.

`OrderNotificationService`가 현재 로그 출력 스텁이므로 운영상 무해하지만, 실제 이메일/SMS 연동이 들어가는 즉시:
- 매 주문/취소마다 알림이 **최소 2회** 발송
- Outbox 재시도까지 겹치면 N+1회 (at-least-once)

**수정 방향**:
- 디자인 주석을 따른다면 `OrderPostProcessingListener`에서 `notificationService.send*` 두 호출 제거 → 등급 재계산만 남김
- 또는 반대로 Outbox 핸들러에서 알림을 빼고 ApplicationEvent로 일원화 (단, at-least-once 보장이 약해짐)

---

## Medium

### 4. V3 CAS UPDATE의 inventory history before/after 스냅샷 부정확

**위치**: `src/main/java/com/shop/domain/order/service/stock/V3CasUpdateStockDeduction.java:55-75`

**관찰**: 록 없는 `SELECT stock_quantity`로 `beforeStock`을 먼저 읽은 뒤, CAS UPDATE를 수행하고 `afterStock = beforeStock - quantity`로 산출한다.

SELECT와 UPDATE 사이 다른 트랜잭션이 같은 행을 갱신하면:
- DB의 실제 재고는 정상 (CAS UPDATE가 원자적이므로)
- 하지만 `DeductionResult`에 담기는 before/after는 **그 사이 어떤 값을 누락한 잘못된 스냅샷**이 된다

**영향 범위**: `OrderPostProcessor.finalizeOrder`가 이 스냅샷으로 `ProductInventoryHistory`를 저장(`OrderPostProcessor.java:55-63`)하므로, 재고 추적 감사에서 "1000 → 990" 같은 기록이 실제로는 "1000 → 800(다른 트랜잭션 100건 처리) → 790"이었던 식으로 왜곡된다. 사후 인벤토리 조정/디버깅 시 혼란.

V1 비관 잠금 경로에는 해당 문제가 없다(잠금 후 읽기).

**수정 방향**: PostgreSQL의 `UPDATE ... RETURNING stock_quantity`를 사용하여 차감 후 값을 직접 반환받고, before는 `after + quantity`로 역산. 한 번의 라운드트립으로 정확한 스냅샷 확보.

---

### 5. `OrderPostProcessor`의 PointHistory.balanceAfter 의미 모호

**위치**: `src/main/java/com/shop/domain/order/service/OrderPostProcessor.java:78-84`

**관찰**: `PointHistory`의 `balanceAfter` 필드로 `user.getPointBalance()`를 전달한다. 이 시점 user.pointBalance는 이미 `OrderCreationService.processPointsUsage`에서 `user.usePoints(usePoints)`가 적용된 사후값이라 현재는 의미가 맞다.

**잠재적 회귀**: 같은 트랜잭션 안에서 use+earn을 모두 처리하도록 향후 정책이 바뀌면 (예: 적립 시점을 다시 주문 시점으로 변경), `OrderService.settleEarnedPoints`(`OrderService.java:325-331`)도 같은 user 객체에 대해 `addPoints` 후 동일 패턴으로 history를 기록한다. 결과적으로 **PointHistory 두 행이 동일한 최종 사후값을 가리키는 회귀**가 발생할 수 있다.

**수정 방향**: 각 변동 직후 즉시 사후값을 캡처하도록 `User.usePoints(int)`가 사후 잔액을 반환하게 변경하거나, PointHistory 생성 시 `balanceBefore`를 함께 기록.

---

### 6. `OrderInvariantValidator`가 totalAmount/finalAmount 등식 미검증

**위치**: `src/main/java/com/shop/domain/order/validation/OrderInvariantValidator.java:20-24`

**관찰**: `validateBeforePersist`는 다음만 검증한다:
- `discount_amount == tier_discount_amount + coupon_discount_amount`
- `refunded_amount ≤ final_amount`
- `refunded_points ≤ used_points`

검증되지 않는 것:
- `final_amount == total_amount - discount_amount - used_points + shipping_fee`
- `sum(orderItem.subtotal) == total_amount`

플래시세일 경로(`validateFlashSaleOrder`)는 더 엄격하다. 일반 경로가 느슨해서 `OrderCreationService.buildAndSaveOrder`의 계산 회귀가 발생하면 DB CHECK 제약에 도달하기 전 단계에서 잡지 못한다. CHECK 제약이 빠진 경우 회귀가 운영까지 도달 가능.

**수정 방향**: 일반 주문에도 두 등식을 검증 항목으로 추가. 플래시세일과 공통 부분은 helper로 추출.

---

### 7. `userCouponRepository.findByOrderId`의 단일 결과 가정

**위치**: `src/main/java/com/shop/domain/coupon/repository/UserCouponRepository.java:56`

**관찰**: `Optional<UserCoupon> findByOrderId(Long orderId)` 시그니처는 "1주문에 최대 1쿠폰" 도메인 규칙을 가정한다. 현재 운영 정책상 맞지만, `user_coupons.order_id`에 UNIQUE 제약이 걸렸는지 코드에 명시되지 않았다.

데이터 마이그레이션, 관리자 직접 수정, 향후 정책 변경 등으로 같은 orderId에 2행이 생기면 Spring Data가 `IncorrectResultSizeDataAccessException`을 던지고, 이는 다음 경로에서 모두 폭발한다:
- `OrderCancellationService.cancelOrderInternal:193` — **취소 자체가 막힘**
- `PartialCancellationService.transitionIfFullyCancelled:462` — **부분취소 막힘**

**수정 방향**:
- 스키마에 `user_coupons.order_id` UNIQUE 제약 확인 (없다면 추가)
- 또는 시그니처를 `List<UserCoupon>`로 변경 후 사용처에서 명시적으로 처리

---

## Low

### 8. `entityManager.refresh(product)`로 락 보유 시간 증가

**위치**:
- `OrderCancellationService.java:138`
- `PartialCancellationService.java:312`

**관찰**: `findByIdWithLock`(PESSIMISTIC_WRITE) 직후 `refresh`를 호출. SELECT FOR UPDATE는 이미 최신값을 가져오므로 의미가 약하다. 1차 캐시 잔존 우려에 대한 방어이긴 하지만, 잠금 후 다시 SELECT 한 번을 더 발생시켜 락 보유 시간이 늘어난다.

**수정 방향**: 1차 캐시 우회가 목적이라면 `OrderStockProcessor:37`처럼 `entityManager.detach(cart.getProduct())`로 락 획득 전에 우회. 락 후의 refresh는 제거.

---

### 9. advisory lock + product lock 간 데드락 잠재성

**위치**:
- 주문 경로: `OrderCartSelectionResolver.java:24` (advisory) → `OrderStockProcessor.java:40` (product)
- 장바구니 경로: `CartService.java:57,78,109,116` (advisory만)

**관찰**: 현재는 cart 경로가 advisory만 잡으므로 락 순서 충돌이 없다. 그러나 다음 시나리오에서 데드락:
- (가설) 향후 cart 작업 중 product 락이 추가되면, OrderCreation은 advisory→product 순, CartService는 product→advisory 순이 되어 양방향 대기 가능

**수정 방향**: 락 순서 규약을 ADR 또는 CLAUDE.md "Key Patterns"에 명문화. 예: "락 순서: advisory(user-scoped) → user → product → order".

---

### 10. Outbox 폴러의 단일 트랜잭션 범위 — 캐시 evict 부수효과

**위치**: `src/main/java/com/shop/global/outbox/OutboxEventPoller.java:126-175`

**관찰**: `pollAndProcess` 전체가 `@Transactional`이며, 안에서 각 이벤트마다 `processSingleEvent`가 외부 핸들러(`StockChangedEventHandler.handle` → `productCacheEvictHelper`)를 호출한다. 캐시 evict 같은 외부 부수효과는 트랜잭션 커밋 전에 발생하므로, 폴러 트랜잭션이 마지막에 롤백되면 outbox 행은 PENDING인데 캐시는 이미 evict된 상태가 된다.

캐시 evict는 멱등이라 현재 문제는 없다. 그러나 향후 외부 알림 핸들러를 동기로 추가하면 **알림은 발송되었는데 outbox는 PENDING(다음 폴링에서 재발송)**이 가능하다.

**수정 방향**: 외부 부수효과를 가지는 핸들러는 트랜잭션 커밋 후로 옮기거나(예: `TransactionalEventListener`), 이벤트별 트랜잭션 경계를 분리(`processSingleEvent` 자체에 `REQUIRES_NEW`).

---

## 권장 조치 우선순위

1. **#1 V4 Redis 차감의 트랜잭션 동기화** — 운영 모드(`shop.backend=redis`)로 전환 시 즉시 손실 발생. V4 활성화 전 필수.
2. **#2 `transitionIfFullyCancelled`에 `publishOrderCancelled` 추가** — 1-2줄 수정으로 알림 유실 차단.
3. **#3 알림 호출 중복 제거** — 디자인 주석과 동기화. 알림 연동 시점에 회귀 가능성 큼.
4. **#4 V3 history 스냅샷 정확도 개선** — `UPDATE ... RETURNING`으로 통합.
5. **#7 `user_coupons.order_id` UNIQUE 인덱스 확인** — 스키마 정합성 가드.

## 검토 한계

- 정적 분석만 수행: 동시성 시나리오를 실제로 재현하지 않았다. #1, #4의 시나리오는 race 발생 가능성을 코드 흐름으로만 추론.
- 스키마 직접 점검 미수행: #7 UNIQUE 제약 유무는 코드 레벨에서 확인 불가.
- 테스트 코드 회귀 검증 미수행: 위 수정이 기존 테스트에 미치는 영향은 별도 확인 필요.

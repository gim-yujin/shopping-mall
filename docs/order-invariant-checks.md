# 운영가이드: 주문 불변식 점검 쿼리

대상 독자: 운영자

주문 정합성 불변식을 운영 배치에서 주기적으로 점검하기 위한 조회 SQL입니다.

## 점검 대상 불변식

- `discount_amount = tier_discount_amount + coupon_discount_amount`
- `refunded_amount <= final_amount`
- `refunded_points <= used_points`

## 단일 점검 쿼리 (불일치 행 탐지)

```sql
SELECT
    o.order_id,
    o.order_number,
    o.discount_amount,
    o.tier_discount_amount,
    o.coupon_discount_amount,
    o.final_amount,
    o.refunded_amount,
    o.used_points,
    o.refunded_points,
    o.order_status,
    o.order_date
FROM orders o
WHERE o.discount_amount <> (o.tier_discount_amount + o.coupon_discount_amount)
   OR o.refunded_amount > o.final_amount
   OR o.refunded_points > o.used_points
ORDER BY o.order_id DESC;
```

## 배치 루틴 반영 가이드

- 일 배치(예: 새벽 1회)로 위 쿼리를 실행한다.
- 결과가 1건 이상이면 운영 알림(슬랙/메일)과 함께 즉시 조사한다.
- 애플리케이션 사전 검증 + DB CHECK 제약으로 신규 위반은 차단되므로,
  조회 결과는 주로 레거시 데이터/수동 DB 변경 여부 점검 용도로 활용한다.


## 기존 위반 데이터 정리 후 VALIDATE 절차

`V9__add_order_invariant_checks.sql`은 운영 DB의 기존 위반 데이터 때문에 배포가 막히지 않도록
`NOT VALID`로 제약을 추가한다. 이 상태에서도 **신규/변경 데이터는 즉시 제약 검사를 받는다**.

기존 데이터 정리 후에는 아래를 실행해 제약을 fully-validated 상태로 전환한다.

```sql
ALTER TABLE orders VALIDATE CONSTRAINT chk_discount_breakdown;
ALTER TABLE orders VALIDATE CONSTRAINT chk_refunded_amount_limit;
ALTER TABLE orders VALIDATE CONSTRAINT chk_refunded_points_limit;
```

## [Phase 23-3] 플래시 세일 주문 정합성 점검

플래시 세일 주문은 일반 주문 경로(쿠폰·포인트·티어할인·배송비)를 모두 우회하므로
다른 검증 규칙이 적용된다. 애플리케이션 레벨에서는 `OrderInvariantValidator.validateFlashSaleOrder(...)`
가 저장 전 검증을 수행하지만, 운영 점검을 위한 SQL은 다음과 같다.

### 점검 1 — 세일 주문 메타 일치
세일 주문(`flash_sale_purchases.order_id`)이 다음 조건을 모두 만족하는지 확인한다.

```sql
SELECT o.order_id, o.order_number, o.total_amount, o.discount_amount,
       o.coupon_discount_amount, o.tier_discount_amount,
       o.shipping_fee, o.used_points, o.earned_points_snapshot
FROM orders o
JOIN flash_sale_purchases fsp ON fsp.order_id = o.order_id
WHERE  o.discount_amount        <> 0
   OR  o.coupon_discount_amount <> 0
   OR  o.tier_discount_amount   <> 0
   OR  o.shipping_fee           <> 0
   OR  o.used_points            <> 0
   OR  o.earned_points_snapshot <> 0;
```

0행이면 OK. 1행 이상이면 일반 주문 경로가 잘못 호출되었거나 데이터가 수동으로 변경된 것이다.

### 점검 2 — 재고 vs 성공 건수 일치 (§8-3 정합성)
세일 종료 후 (allocated − remaining)이 성공 구매 건수와 일치하는지 확인한다.

```sql
SELECT fsi.flash_sale_item_id,
       fsi.allocated_quantity - fsi.remaining_quantity AS sold,
       (SELECT COUNT(*) FROM flash_sale_purchases fsp
         WHERE fsp.flash_sale_id = fsi.flash_sale_id) AS purchases
FROM flash_sale_items fsi
WHERE (fsi.allocated_quantity - fsi.remaining_quantity)
   <> (SELECT COUNT(*) FROM flash_sale_purchases fsp
        WHERE fsp.flash_sale_id = fsi.flash_sale_id);
```

0행이면 정합성 OK. 0행이 아니면 보상 경로(`restoreAtomic`)가 동작하지 않았거나 직접 SQL 변경이 있었다.

### 점검 3 — 1인 1구매 강제
DB UNIQUE(`uk_fsp_user_sale`)가 활성 상태인지 점검한다.

```sql
SELECT flash_sale_id, user_id, COUNT(*) AS dup
FROM flash_sale_purchases
GROUP BY flash_sale_id, user_id
HAVING COUNT(*) > 1;
```

0행이 정상. 1행 이상은 UNIQUE 제약이 누락된 환경(스키마 드리프트)을 의미한다.

## [Phase 23-5] 플래시 세일 주문 취소 보상 정합성

§13-2 #6 해소 이후, 플래시 세일 주문 취소는 일반 보상 경로를 우회하고 `flash_sale_items.remaining_quantity` 복원 + `flash_sale_purchases` 삭제로만 끝난다. 운영에서 보상 누락을 감지하기 위한 점검 SQL.

### 점검 4 — `order_origin` 마커 정합성
`flash_sale_purchases`에 등재된 주문은 모두 `order_origin = 'FLASH_SALE'`이어야 한다. V24 적용 이전 데이터 또는 수동 변경의 흔적을 찾는다.

```sql
SELECT o.order_id, o.order_number, o.order_origin
FROM orders o
JOIN flash_sale_purchases fsp ON fsp.order_id = o.order_id
WHERE o.order_origin <> 'FLASH_SALE';
```

0행이면 OK. 1행 이상이면 V24 마이그레이션 누락 또는 데이터 정합 이상.

### 점검 5 — CANCELLED 상태이지만 보상이 안 된 행
플래시 세일 주문이 `CANCELLED`로 전이됐는데 `flash_sale_purchases` 행이 아직 살아 있다면, 동기 리스너가 동작하지 않았거나 운영자가 수동 cancel 한 경우다.

```sql
SELECT o.order_id, o.cancelled_at, fsp.flash_sale_purchase_id, fsp.flash_sale_item_id
FROM orders o
JOIN flash_sale_purchases fsp ON fsp.order_id = o.order_id
WHERE o.order_origin = 'FLASH_SALE'
  AND o.order_status = 'CANCELLED';
```

0행이 정상. 1행 이상이면 해당 주문에 대해 수동 보상이 필요하다.

복구 SQL(주문 1건):
```sql
BEGIN;
-- 1) remaining_quantity 복원
UPDATE flash_sale_items
   SET remaining_quantity = remaining_quantity + 1, version = version + 1
 WHERE flash_sale_item_id = (SELECT flash_sale_item_id FROM flash_sale_purchases WHERE order_id = :orderId);
-- 2) purchase 행 삭제 (UNIQUE 해제)
DELETE FROM flash_sale_purchases WHERE order_id = :orderId;
COMMIT;
```

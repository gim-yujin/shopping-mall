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

## PointHistory ↔ Order 정합성 점검

`point_history` 테이블은 모든 포인트 변동의 단일 진실원이다. 주문 1건의 USE/REFUND/EARN 이력 합산이
`orders` 테이블의 대응 컬럼과 일치해야 한다. 부분 취소/반품 경로에서는 REFUND 행이 여러 건 발생할 수
있으므로 운영 점검은 합산 비교가 핵심이다.

### 점검 6 — USE 합 = `orders.used_points`
주문 시 사용 포인트는 `OrderPostProcessor`에서 단일 USE 행으로 기록된다(usedPoints>0인 경우).

```sql
SELECT o.order_id, o.order_number, o.used_points,
       COALESCE(s.use_sum, 0) AS history_use_sum,
       o.used_points - COALESCE(s.use_sum, 0) AS diff
FROM orders o
LEFT JOIN (
    SELECT reference_id, SUM(amount) AS use_sum
      FROM point_history
     WHERE change_type = 'USE' AND reference_type = 'ORDER'
     GROUP BY reference_id
) s ON s.reference_id = o.order_id
WHERE o.used_points <> COALESCE(s.use_sum, 0)
  AND o.used_points > 0;
```

0행이 정상. 1행 이상이면 주문 생성 시 USE 행 누락 또는 수동 변경.

### 점검 7 — REFUND 합 = `orders.refunded_points`
부분 취소(`PARTIAL_CANCEL`), 반품(`RETURN`), 전체 취소(`CANCEL`) 모든 경로의 REFUND 행을 합산한다.

```sql
SELECT o.order_id, o.order_number, o.refunded_points,
       COALESCE(s.refund_sum, 0) AS history_refund_sum,
       o.refunded_points - COALESCE(s.refund_sum, 0) AS diff
FROM orders o
LEFT JOIN (
    SELECT reference_id, SUM(amount) AS refund_sum
      FROM point_history
     WHERE change_type = 'REFUND'
       AND reference_type IN ('CANCEL', 'PARTIAL_CANCEL', 'RETURN')
     GROUP BY reference_id
) s ON s.reference_id = o.order_id
WHERE o.refunded_points <> COALESCE(s.refund_sum, 0);
```

0행이 정상. 1행 이상이면 부분 취소/반품/전체 취소 트랜잭션이 비원자적으로 실패했거나 수동 변경.

### 점검 8 — EARN 합 = effective earned (DELIVERED & settled)
배송 완료 후 적립 포인트는 `OrderService.settleEarnedPoints`가 `(finalAmount - refundedAmount) ×
earnRate`로 계산한 단일 EARN 행으로 기록된다. `points_settled = true`인 주문만 검증 대상.

```sql
SELECT o.order_id, o.order_number,
       o.earned_points_snapshot, o.refunded_amount, o.final_amount,
       COALESCE(s.earn_sum, 0) AS history_earn_sum
FROM orders o
LEFT JOIN (
    SELECT reference_id, SUM(amount) AS earn_sum
      FROM point_history
     WHERE change_type = 'EARN' AND reference_type = 'ORDER'
     GROUP BY reference_id
) s ON s.reference_id = o.order_id
WHERE o.points_settled = TRUE
  AND COALESCE(s.earn_sum, 0) > o.earned_points_snapshot;
```

EARN 합이 `earned_points_snapshot`(부분 취소 미반영 상한)을 초과하면 정합성 위반.
정확한 일치 검증은 부분취소 비율 계산이 필요하므로 운영 점검은 상한 초과만 확인한다.

### 운영 가이드
- 일 배치(예: 새벽 1회)로 점검 6/7 실행. 결과 0행 = OK, 1행 이상 = 알림 + 즉시 조사.
- 점검 8은 주간 배치로 실행. 부분 취소가 빈번한 주문(`refunded_amount > 0`)은 별도 표본 정밀 검증 권장.
- `PointHistoryRepository.sumRefundedPointsByOrderId(orderId)` / `sumUsedPointsByOrderId(orderId)`로
  애플리케이션 단건 점검도 가능. CS 문의 대응 시 사용.

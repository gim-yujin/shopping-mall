# 주문 불변식 운영 점검 쿼리

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

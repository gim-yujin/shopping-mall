-- 주문 결제수단 비정상 데이터 점검
SELECT payment_method, COUNT(*) AS count
FROM orders
GROUP BY payment_method
HAVING payment_method IS NULL
    OR payment_method NOT IN ('CARD', 'BANK', 'KAKAO', 'NAVER', 'PAYCO');

-- 필요 시 정정(마이그레이션) 예시: 비정상 값을 기본 결제수단(CARD)로 보정
-- BEGIN;
-- UPDATE orders
-- SET payment_method = 'CARD'
-- WHERE payment_method IS NULL
--    OR payment_method NOT IN ('CARD', 'BANK', 'KAKAO', 'NAVER', 'PAYCO');
-- COMMIT;

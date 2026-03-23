-- 리뷰 중복 정책 보강 마이그레이션
-- 1) 기존 구매 리뷰(order_item_id NOT NULL)는 uk_review_user_order_item으로 보호
-- 2) 비구매 리뷰(order_item_id IS NULL)는 사용자/상품당 1건으로 제한
-- 3) 이미 중복 데이터가 존재할 수 있으므로 인덱스 생성 전에 정리 수행

BEGIN;

LOCK TABLE reviews IN SHARE ROW EXCLUSIVE MODE;

WITH duplicate_reviews AS (
    SELECT review_id
    FROM (
        SELECT review_id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id, product_id
                   ORDER BY created_at DESC, review_id DESC
               ) AS rn
        FROM reviews
        WHERE order_item_id IS NULL
    ) ranked
    WHERE rn > 1
)
DELETE FROM reviews
WHERE review_id IN (SELECT review_id FROM duplicate_reviews);

CREATE UNIQUE INDEX IF NOT EXISTS uk_review_user_product_without_order_item
    ON reviews(user_id, product_id)
    WHERE order_item_id IS NULL;

COMMIT;

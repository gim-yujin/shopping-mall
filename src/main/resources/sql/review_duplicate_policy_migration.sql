-- 리뷰 중복 정책 보강 마이그레이션
-- 1) 기존 구매 리뷰(order_item_id NOT NULL)는 uk_review_user_order_item으로 보호
-- 2) 비구매 리뷰(order_item_id IS NULL)는 사용자/상품당 1건으로 제한

CREATE UNIQUE INDEX IF NOT EXISTS uk_review_user_product_without_order_item
    ON reviews(user_id, product_id)
    WHERE order_item_id IS NULL;

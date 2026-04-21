-- ============================================================================
-- C2 롤백: 성능 인덱스 58개 전부 제거
-- ============================================================================
-- 목적: 캐시 OFF 상태에서 인덱스 전략 전체의 순수 효과를 측정
-- 대상: schema.sql의 idx_* 인덱스 58개 (PK, UNIQUE 제약/uk_ 인덱스는 유지)
--
-- 실행:
--   sudo -u postgres psql -d shopping_mall_db -f drop-indexes-c2.sql
--
-- 복원:
--   sudo -u postgres psql -d shopping_mall_db -f restore-indexes-c2.sql
--
-- 주의:
--   - 100만건 테이블에서 인덱스 제거 후 쿼리가 극도로 느려질 수 있음
--   - 반드시 테스트 후 restore-indexes-c2.sql로 복원할 것
--   - PK + UNIQUE 제약/uk_* 인덱스는 유지되므로 앱 무결성은 보장됨
--   - pg_trgm extension 자체는 제거하지 않는다 (다른 쿼리에 영향 가능성).
--
-- 정합성 기준:
--   schema.sql의 idx_* 58개와 1:1 대응 (2026-04-21, V21/V22 반영).
-- ============================================================================

BEGIN;

-- ── review_helpfuls (1개) ──
-- 참고: uk_helpful_review_user (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_review_helpful_user;

-- ── users (3개) ──
-- 참고: users_username_key, users_email_key, uk_users_username_lower (UNIQUE)는 유지됨
--       → 로그인(findByUsername) 성능은 UNIQUE 인덱스가 커버
DROP INDEX IF EXISTS idx_users_tier;
DROP INDEX IF EXISTS idx_users_created;
DROP INDEX IF EXISTS idx_users_total_spent;

-- ── user_tier_history (2개) ──
DROP INDEX IF EXISTS idx_tier_history_user;
DROP INDEX IF EXISTS idx_tier_history_date;

-- ── categories (2개) ──
DROP INDEX IF EXISTS idx_category_parent;
DROP INDEX IF EXISTS idx_category_level;

-- ── products (10개) — 가장 큰 영향 예상 ──
DROP INDEX IF EXISTS idx_product_name_gin;
DROP INDEX IF EXISTS idx_product_name_trgm;
DROP INDEX IF EXISTS idx_product_category;
DROP INDEX IF EXISTS idx_product_price;
DROP INDEX IF EXISTS idx_product_sales;
DROP INDEX IF EXISTS idx_product_created;
DROP INDEX IF EXISTS idx_product_rating;
DROP INDEX IF EXISTS idx_product_price_active;
DROP INDEX IF EXISTS idx_product_review_count;
DROP INDEX IF EXISTS idx_product_deals;

-- ── product_images (2개) ──
DROP INDEX IF EXISTS idx_image_product;
DROP INDEX IF EXISTS idx_image_thumbnail;

-- ── orders (4개) ──
-- 참고: orders_order_number_key (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_order_user;
DROP INDEX IF EXISTS idx_order_status;
DROP INDEX IF EXISTS idx_order_date;
DROP INDEX IF EXISTS idx_order_yearly_spent_non_cancelled;

-- ── order_items (5개) ──
DROP INDEX IF EXISTS idx_order_items_order;
DROP INDEX IF EXISTS idx_order_items_product;
DROP INDEX IF EXISTS idx_order_items_created;
DROP INDEX IF EXISTS idx_order_items_covering;
DROP INDEX IF EXISTS idx_order_items_status_return_requested;

-- ── carts (2개) ──
-- 참고: uk_cart_user_product (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_cart_user;
DROP INDEX IF EXISTS idx_cart_product;

-- ── wishlists (2개) ──
-- 참고: uk_wishlist_user_product (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_wishlist_user;
DROP INDEX IF EXISTS idx_wishlist_product;

-- ── coupons (1개) ──
-- 참고: coupons_coupon_code_key (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_coupon_valid;

-- ── user_coupons (3개) ──
-- 참고: uk_user_coupon_user_coupon (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_user_coupon_user;
DROP INDEX IF EXISTS idx_user_coupon_coupon;
DROP INDEX IF EXISTS idx_user_coupon_order;

-- ── reviews (4개) ──
-- 참고: uk_review_user_order_item, uk_review_user_product_without_order_item (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_review_product;
DROP INDEX IF EXISTS idx_review_user;
DROP INDEX IF EXISTS idx_review_rating;
DROP INDEX IF EXISTS idx_review_content_gin;

-- ── product_inventory_history (3개) ──
DROP INDEX IF EXISTS idx_inventory_product;
DROP INDEX IF EXISTS idx_inventory_date;
DROP INDEX IF EXISTS idx_inventory_type;

-- ── search_logs (4개) ──
DROP INDEX IF EXISTS idx_search_keyword;
DROP INDEX IF EXISTS idx_search_user;
DROP INDEX IF EXISTS idx_search_date;
DROP INDEX IF EXISTS idx_search_date_keyword;

-- ── point_history (5개) ──
DROP INDEX IF EXISTS idx_point_history_user;
DROP INDEX IF EXISTS idx_point_history_reference;
DROP INDEX IF EXISTS idx_point_history_ref_order;
DROP INDEX IF EXISTS idx_point_history_type_created;
DROP INDEX IF EXISTS idx_point_history_created;

-- ── idempotency_records (1개) ──
-- 참고: uk_idempotency_user_key (UNIQUE)는 유지됨
DROP INDEX IF EXISTS idx_idempotency_created;

-- ── outbox_events (4개) ──
DROP INDEX IF EXISTS idx_outbox_pending;
DROP INDEX IF EXISTS idx_outbox_processed_at;
DROP INDEX IF EXISTS idx_outbox_dead_letter;
DROP INDEX IF EXISTS idx_outbox_retry;

COMMIT;

-- ── 확인 ──
SELECT COUNT(*) AS remaining_indexes
FROM pg_indexes
WHERE schemaname = 'public' AND indexname LIKE 'idx_%';
-- 기대값: 0

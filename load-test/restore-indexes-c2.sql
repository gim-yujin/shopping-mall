-- ============================================================================
-- C2 복원: 성능 인덱스 58개 재생성
-- ============================================================================
-- 목적: C2 테스트 완료 후 인덱스를 원래 상태로 복원
--
-- 실행:
--   sudo -u postgres psql -d shopping_mall_db -f restore-indexes-c2.sql
--
-- 소요 시간: 100만건 테이블 기준 수 분 소요 예상 (GIN 인덱스는 더 오래 걸림)
--
-- 정합성 기준:
--   schema.sql의 idx_* 58개와 1:1 대응 (2026-04-21, V21/V22 반영).
--   대체 경로: `psql -f src/main/resources/schema.sql`로 스키마 전체를 재적용해도 되지만,
--   기존 테이블/데이터를 보존한 채 인덱스만 복원하려면 본 스크립트를 사용한다.
-- ============================================================================

-- pg_trgm extension (idx_product_name_trgm 전제). 이미 존재하면 무시된다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── review_helpfuls (1개) ──
CREATE INDEX IF NOT EXISTS idx_review_helpful_user ON review_helpfuls(user_id, review_id);

-- ── users (3개) ──
CREATE INDEX IF NOT EXISTS idx_users_tier ON users(tier_id);
CREATE INDEX IF NOT EXISTS idx_users_created ON users(created_at);
CREATE INDEX IF NOT EXISTS idx_users_total_spent ON users(total_spent DESC);

-- ── user_tier_history (2개) ──
CREATE INDEX IF NOT EXISTS idx_tier_history_user ON user_tier_history(user_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_tier_history_date ON user_tier_history(changed_at);

-- ── categories (2개) ──
CREATE INDEX IF NOT EXISTS idx_category_parent ON categories(parent_category_id);
CREATE INDEX IF NOT EXISTS idx_category_level ON categories(level, display_order);

-- ── products (10개) ──
CREATE INDEX IF NOT EXISTS idx_product_name_gin ON products USING gin(to_tsvector('simple', product_name));
CREATE INDEX IF NOT EXISTS idx_product_name_trgm ON products USING gin(LOWER(product_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_product_category ON products(category_id, is_active, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_price ON products(price);
CREATE INDEX IF NOT EXISTS idx_product_sales ON products(is_active, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_created ON products(is_active, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_product_rating ON products(is_active, rating_avg DESC, review_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_price_active ON products(is_active, price);
CREATE INDEX IF NOT EXISTS idx_product_review_count ON products(is_active, review_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_deals ON products ((original_price - price) DESC)
    WHERE is_active = true AND original_price IS NOT NULL AND original_price > price;

-- ── product_images (2개) ──
CREATE INDEX IF NOT EXISTS idx_image_product ON product_images(product_id, image_order);
CREATE INDEX IF NOT EXISTS idx_image_thumbnail ON product_images(product_id) WHERE is_thumbnail = true;

-- ── orders (4개) ──
CREATE INDEX IF NOT EXISTS idx_order_user ON orders(user_id, order_date DESC);
CREATE INDEX IF NOT EXISTS idx_order_status ON orders(order_status, order_date);
CREATE INDEX IF NOT EXISTS idx_order_date ON orders(order_date DESC);
CREATE INDEX IF NOT EXISTS idx_order_yearly_spent_non_cancelled
    ON orders(order_date)
    INCLUDE (user_id, final_amount)
    WHERE order_status <> 'CANCELLED';

-- ── order_items (5개) ──
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items(product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_items_created ON order_items(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_items_covering
    ON order_items(product_id, created_at)
    INCLUDE (quantity, subtotal);
CREATE INDEX IF NOT EXISTS idx_order_items_status_return_requested
    ON order_items (status)
    WHERE status = 'RETURN_REQUESTED';

-- ── carts (2개) ──
CREATE INDEX IF NOT EXISTS idx_cart_user ON carts(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_cart_product ON carts(product_id);

-- ── wishlists (2개) ──
CREATE INDEX IF NOT EXISTS idx_wishlist_user ON wishlists(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wishlist_product ON wishlists(product_id);

-- ── coupons (1개) ──
CREATE INDEX IF NOT EXISTS idx_coupon_valid ON coupons(valid_from, valid_until, is_active);

-- ── user_coupons (3개) ──
CREATE INDEX IF NOT EXISTS idx_user_coupon_user ON user_coupons(user_id, is_used, expires_at);
CREATE INDEX IF NOT EXISTS idx_user_coupon_coupon ON user_coupons(coupon_id);
CREATE INDEX IF NOT EXISTS idx_user_coupon_order ON user_coupons(order_id)
    WHERE order_id IS NOT NULL;

-- ── reviews (4개) ──
CREATE INDEX IF NOT EXISTS idx_review_product ON reviews(product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_user ON reviews(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_rating ON reviews(product_id, rating);
CREATE INDEX IF NOT EXISTS idx_review_content_gin ON reviews USING gin(to_tsvector('simple', content));

-- ── product_inventory_history (3개) ──
CREATE INDEX IF NOT EXISTS idx_inventory_product ON product_inventory_history(product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_inventory_date ON product_inventory_history(created_at);
CREATE INDEX IF NOT EXISTS idx_inventory_type ON product_inventory_history(change_type, created_at);

-- ── search_logs (4개) ──
CREATE INDEX IF NOT EXISTS idx_search_keyword ON search_logs(search_keyword, searched_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_user ON search_logs(user_id, searched_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_date ON search_logs(searched_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_date_keyword ON search_logs(searched_at DESC, search_keyword);

-- ── point_history (5개) ──
CREATE INDEX IF NOT EXISTS idx_point_history_user ON point_history(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_point_history_reference ON point_history(reference_type, reference_id);
CREATE INDEX IF NOT EXISTS idx_point_history_ref_order ON point_history(reference_id, reference_type, created_at);
CREATE INDEX IF NOT EXISTS idx_point_history_type_created ON point_history(change_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_point_history_created ON point_history(created_at DESC);

-- ── idempotency_records (1개) ──
CREATE INDEX IF NOT EXISTS idx_idempotency_created ON idempotency_records(created_at);

-- ── outbox_events (4개) ──
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_outbox_processed_at ON outbox_events(processed_at) WHERE status = 'PROCESSED';
CREATE INDEX IF NOT EXISTS idx_outbox_dead_letter ON outbox_events(processed_at) WHERE status = 'DEAD_LETTER';
CREATE INDEX IF NOT EXISTS idx_outbox_retry ON outbox_events(next_retry_at)
    WHERE status = 'PENDING' AND next_retry_at IS NOT NULL;

-- ── flash_sales / flash_sale_items / flash_sale_purchases (3개) ──
CREATE INDEX IF NOT EXISTS idx_flash_sale_status_start ON flash_sales(status, start_time);
CREATE INDEX IF NOT EXISTS idx_fsi_flash_sale          ON flash_sale_items(flash_sale_id);
CREATE INDEX IF NOT EXISTS idx_fsp_flash_sale          ON flash_sale_purchases(flash_sale_id, purchased_at DESC);

-- ── 확인 ──
SELECT COUNT(*) AS restored_indexes
FROM pg_indexes
WHERE schemaname = 'public' AND indexname LIKE 'idx_%';
-- 기대값: 61 (schema.sql idx_* 전체)

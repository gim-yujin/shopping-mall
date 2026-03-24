-- ============================================================================
-- C2 복원: 성능 인덱스 47개 재생성
-- ============================================================================
-- 목적: C2 테스트 완료 후 인덱스를 원래 상태로 복원
--
-- 실행:
--   sudo -u postgres psql -d shopping_mall_db -f restore-indexes-c2.sql
--
-- 소요 시간: 100만건 테이블 기준 수 분 소요 예상 (GIN 인덱스는 더 오래 걸림)
-- ============================================================================

-- ── review_helpfuls ──
CREATE INDEX IF NOT EXISTS idx_review_helpful_review ON review_helpfuls(review_id);
CREATE INDEX IF NOT EXISTS idx_review_helpful_user ON review_helpfuls(user_id);

-- ── users ──
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_tier ON users(tier_id);
CREATE INDEX IF NOT EXISTS idx_users_created ON users(created_at);
CREATE INDEX IF NOT EXISTS idx_users_total_spent ON users(total_spent DESC);

-- ── user_tier_history ──
CREATE INDEX IF NOT EXISTS idx_tier_history_user ON user_tier_history(user_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_tier_history_date ON user_tier_history(changed_at);

-- ── categories ──
CREATE INDEX IF NOT EXISTS idx_category_parent ON categories(parent_category_id);
CREATE INDEX IF NOT EXISTS idx_category_level ON categories(level, display_order);

-- ── products ──
CREATE INDEX IF NOT EXISTS idx_product_name_gin ON products USING gin(to_tsvector('simple', product_name));
CREATE INDEX IF NOT EXISTS idx_product_category ON products(category_id, is_active, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_price ON products(price);
CREATE INDEX IF NOT EXISTS idx_product_sales ON products(is_active, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_created ON products(is_active, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_product_rating ON products(is_active, rating_avg DESC, review_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_price_active ON products(is_active, price);
CREATE INDEX IF NOT EXISTS idx_product_review_count ON products(is_active, review_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_deals ON products ((original_price - price) DESC)
    WHERE is_active = true AND original_price IS NOT NULL AND original_price > price;

-- ── product_images ──
CREATE INDEX IF NOT EXISTS idx_image_product ON product_images(product_id, image_order);

-- ── orders ──
CREATE INDEX IF NOT EXISTS idx_order_user ON orders(user_id, order_date DESC);
CREATE INDEX IF NOT EXISTS idx_order_status ON orders(order_status, order_date);
CREATE INDEX IF NOT EXISTS idx_order_date ON orders(order_date DESC);
CREATE INDEX IF NOT EXISTS idx_order_number ON orders(order_number);

-- ── order_items ──
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items(product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_items_created ON order_items(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_items_covering
    ON order_items(product_id, created_at)
    INCLUDE (quantity, subtotal);

-- ── carts ──
CREATE INDEX IF NOT EXISTS idx_cart_user ON carts(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_cart_product ON carts(product_id);

-- ── wishlists ──
CREATE INDEX IF NOT EXISTS idx_wishlist_user ON wishlists(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wishlist_product ON wishlists(product_id);

-- ── coupons ──
CREATE INDEX IF NOT EXISTS idx_coupon_code ON coupons(coupon_code);
CREATE INDEX IF NOT EXISTS idx_coupon_valid ON coupons(valid_from, valid_until, is_active);

-- ── user_coupons ──
CREATE INDEX IF NOT EXISTS idx_user_coupon_user ON user_coupons(user_id, is_used, expires_at);
CREATE INDEX IF NOT EXISTS idx_user_coupon_coupon ON user_coupons(coupon_id);

-- ── reviews ──
CREATE INDEX IF NOT EXISTS idx_review_product ON reviews(product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_user ON reviews(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_rating ON reviews(product_id, rating);
CREATE INDEX IF NOT EXISTS idx_review_content_gin ON reviews USING gin(to_tsvector('simple', content));

-- ── product_inventory_history ──
CREATE INDEX IF NOT EXISTS idx_inventory_product ON product_inventory_history(product_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_inventory_date ON product_inventory_history(created_at);
CREATE INDEX IF NOT EXISTS idx_inventory_type ON product_inventory_history(change_type, created_at);

-- ── search_logs ──
CREATE INDEX IF NOT EXISTS idx_search_keyword ON search_logs(search_keyword, searched_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_user ON search_logs(user_id, searched_at DESC);
CREATE INDEX IF NOT EXISTS idx_search_date ON search_logs(searched_at DESC);

-- ── 확인 ──
SELECT COUNT(*) AS restored_indexes
FROM pg_indexes
WHERE schemaname = 'public' AND indexname LIKE 'idx_%';
-- 기대값: 47

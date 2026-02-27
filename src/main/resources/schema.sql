-- ============================================================================
-- 대규모 쇼핑몰 데이터베이스 스키마
-- 목적: 성능 최적화 학습용 (1억+ 레코드 처리)
-- DBMS: PostgreSQL 14+
-- ============================================================================

-- 기존 테이블 삭제 (개발 환경에서만 사용)
-- DROP TABLE IF EXISTS user_tier_history CASCADE;
-- DROP TABLE IF EXISTS search_logs CASCADE;
-- DROP TABLE IF EXISTS product_inventory_history CASCADE;
-- DROP TABLE IF EXISTS reviews CASCADE;
-- DROP TABLE IF EXISTS user_coupons CASCADE;
-- DROP TABLE IF EXISTS coupons CASCADE;
-- DROP TABLE IF EXISTS wishlists CASCADE;
-- DROP TABLE IF EXISTS carts CASCADE;
-- DROP TABLE IF EXISTS order_items CASCADE;
-- DROP TABLE IF EXISTS orders CASCADE;
-- DROP TABLE IF EXISTS product_images CASCADE;
-- DROP TABLE IF EXISTS products CASCADE;
-- DROP TABLE IF EXISTS categories CASCADE;
-- DROP TABLE IF EXISTS users CASCADE;
-- DROP TABLE IF EXISTS user_tiers CASCADE;

-- ============================================================================
-- 1. USER_TIERS (회원 등급 정보)
-- ============================================================================
CREATE TABLE user_tiers (
    tier_id SERIAL PRIMARY KEY,
    tier_name VARCHAR(50) NOT NULL,
    tier_level INT UNIQUE NOT NULL,
    min_spent DECIMAL(15, 2) NOT NULL,
    discount_rate DECIMAL(5, 2) DEFAULT 0,
    point_earn_rate DECIMAL(5, 2) DEFAULT 1.0,
    free_shipping_threshold DECIMAL(10, 2),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_tier_level CHECK (tier_level > 0),
    CONSTRAINT chk_discount_rate CHECK (discount_rate >= 0 AND discount_rate <= 100),
    CONSTRAINT chk_point_rate CHECK (point_earn_rate >= 0)
);

COMMENT ON TABLE user_tiers IS '회원 등급 마스터 테이블';
COMMENT ON COLUMN user_tiers.tier_level IS '등급 레벨 (숫자가 높을수록 상위 등급)';
COMMENT ON COLUMN user_tiers.min_spent IS '등급 달성 최소 누적 구매 금액';

-- ============================================================================
-- 2. USERS (사용자)
-- ============================================================================
CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    role VARCHAR(20) DEFAULT 'ROLE_USER' NOT NULL,
    tier_id INT NOT NULL DEFAULT 1,
    total_spent DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    point_balance INT DEFAULT 0 NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_login_at TIMESTAMP,
    
    CONSTRAINT fk_users_tier FOREIGN KEY (tier_id) 
        REFERENCES user_tiers(tier_id),
    CONSTRAINT chk_total_spent CHECK (total_spent >= 0),
    CONSTRAINT chk_point_balance CHECK (point_balance >= 0),
    CONSTRAINT chk_role CHECK (role IN ('ROLE_USER', 'ROLE_ADMIN'))
);

COMMENT ON TABLE users IS '사용자 테이블 (예상: 100만 명)';
COMMENT ON COLUMN users.total_spent IS '누적 구매 금액 (등급 산정 기준)';
COMMENT ON COLUMN users.point_balance IS '현재 보유 포인트';

-- username은 대소문자 비구분 정책(Case-insensitive)을 적용한다.
CREATE UNIQUE INDEX uk_users_username_lower ON users (LOWER(username));

-- ============================================================================
-- 3. USER_TIER_HISTORY (등급 변경 이력)
-- ============================================================================
CREATE TABLE user_tier_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    from_tier_id INT,
    to_tier_id INT NOT NULL,
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reason VARCHAR(100),
    
    CONSTRAINT fk_tier_history_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_tier_history_from FOREIGN KEY (from_tier_id) 
        REFERENCES user_tiers(tier_id),
    CONSTRAINT fk_tier_history_to FOREIGN KEY (to_tier_id) 
        REFERENCES user_tiers(tier_id)
);

COMMENT ON TABLE user_tier_history IS '회원 등급 변경 이력 (예상: 500만 건)';
COMMENT ON COLUMN user_tier_history.reason IS 'PURCHASE_AMOUNT, MANUAL, PROMOTION';

-- ============================================================================
-- 4. CATEGORIES (카테고리)
-- ============================================================================
CREATE TABLE categories (
    category_id SERIAL PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    parent_category_id INT,
    level INT NOT NULL,
    display_order INT DEFAULT 0 NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_category_id) 
        REFERENCES categories(category_id),
    CONSTRAINT chk_category_level CHECK (level BETWEEN 1 AND 3)
);

COMMENT ON TABLE categories IS '카테고리 계층 구조 (예상: 1,000개)';
COMMENT ON COLUMN categories.level IS '1: 대분류, 2: 중분류, 3: 소분류';

-- ============================================================================
-- 5. PRODUCTS (상품)
-- ============================================================================
CREATE TABLE products (
    product_id BIGSERIAL PRIMARY KEY,
    product_name VARCHAR(200) NOT NULL,
    category_id INT NOT NULL,
    description TEXT,
    price DECIMAL(12, 2) NOT NULL,
    original_price DECIMAL(12, 2),
    stock_quantity INT DEFAULT 0 NOT NULL,
    sales_count INT DEFAULT 0 NOT NULL,
    view_count INT DEFAULT 0 NOT NULL,
    rating_avg DECIMAL(3, 2) DEFAULT 0 NOT NULL,
    review_count INT DEFAULT 0 NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) 
        REFERENCES categories(category_id),
    CONSTRAINT chk_price CHECK (price >= 0),
    CONSTRAINT chk_stock CHECK (stock_quantity >= 0),
    CONSTRAINT chk_rating CHECK (rating_avg >= 0 AND rating_avg <= 5)
);

COMMENT ON TABLE products IS '상품 마스터 (예상: 100만 개)';
COMMENT ON COLUMN products.sales_count IS '총 판매 수량 (통계용)';
COMMENT ON COLUMN products.rating_avg IS '평균 평점 (0.00 ~ 5.00)';

-- ============================================================================
-- 6. PRODUCT_IMAGES (상품 이미지)
-- ============================================================================
CREATE TABLE product_images (
    image_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    image_order INT DEFAULT 0 NOT NULL,
    is_thumbnail BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_image_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id) ON DELETE CASCADE
);

COMMENT ON TABLE product_images IS '상품 이미지 (예상: 300만 건, 상품당 평균 3장)';
COMMENT ON COLUMN product_images.image_order IS '0: 대표 이미지, 1~N: 서브 이미지';

-- ============================================================================
-- 7. ORDERS (주문)
-- ============================================================================
CREATE TABLE orders (
    order_id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(50) UNIQUE NOT NULL,
    user_id BIGINT NOT NULL,
    order_status VARCHAR(20) NOT NULL,
    total_amount DECIMAL(15, 2) NOT NULL,
    discount_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    tier_discount_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    coupon_discount_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    shipping_fee DECIMAL(8, 2) DEFAULT 0 NOT NULL,
    final_amount DECIMAL(15, 2) NOT NULL,
    point_earn_rate_snapshot DECIMAL(5, 2) DEFAULT 0 NOT NULL,
    earned_points_snapshot INT DEFAULT 0 NOT NULL,
    used_points INT DEFAULT 0 NOT NULL,
    payment_method VARCHAR(20),
    shipping_address TEXT,
    recipient_name VARCHAR(100),
    recipient_phone VARCHAR(20),
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    paid_at TIMESTAMP,
    shipped_at TIMESTAMP,
    delivered_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id),
    CONSTRAINT chk_order_status CHECK (order_status IN 
        ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT chk_payment_method CHECK (payment_method IN 
        ('CARD', 'BANK', 'KAKAO', 'NAVER', 'PAYCO')),
    CONSTRAINT chk_amounts CHECK (final_amount >= 0)
);

COMMENT ON TABLE orders IS '주문 헤더 (예상: 2천만 건)';
COMMENT ON COLUMN orders.order_number IS '주문 번호 (예: 20240101-XXXXX)';
COMMENT ON COLUMN orders.point_earn_rate_snapshot IS '주문 시점 사용자 등급의 포인트 적립률 스냅샷(%)';
COMMENT ON COLUMN orders.earned_points_snapshot IS '주문 생성 시 실제 적립된 포인트 스냅샷';
COMMENT ON COLUMN orders.used_points IS '주문 시 사용한 포인트 (1P = 1원, 취소 시 환불)';

-- ============================================================================
-- 8. ORDER_ITEMS (주문 상세) ⭐️ 1억 건 주인공
-- ============================================================================
CREATE TABLE order_items (
    order_item_id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    discount_rate DECIMAL(5, 2) DEFAULT 0 NOT NULL,
    subtotal DECIMAL(15, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) 
        REFERENCES orders(order_id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id),
    CONSTRAINT chk_quantity CHECK (quantity > 0),
    CONSTRAINT chk_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_subtotal CHECK (subtotal >= 0)
);

COMMENT ON TABLE order_items IS '⭐️ 주문 상세 - 1억 건 주인공 테이블';
COMMENT ON COLUMN order_items.product_name IS '주문 당시 상품명 스냅샷';
COMMENT ON COLUMN order_items.unit_price IS '주문 당시 가격 스냅샷';

-- ============================================================================
-- 9. CARTS (장바구니)
-- ============================================================================
CREATE TABLE carts (
    cart_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id),
    CONSTRAINT chk_cart_quantity CHECK (quantity > 0),
    CONSTRAINT uk_cart_user_product UNIQUE (user_id, product_id)
);

COMMENT ON TABLE carts IS '장바구니 (예상: 500만 건)';

-- ============================================================================
-- 10. WISHLISTS (찜하기)
-- ============================================================================
CREATE TABLE wishlists (
    wishlist_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_wishlist_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_wishlist_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT uk_wishlist_user_product UNIQUE (user_id, product_id)
);

COMMENT ON TABLE wishlists IS '찜하기 (예상: 1천만 건)';

-- ============================================================================
-- 11. COUPONS (쿠폰)
-- ============================================================================
CREATE TABLE coupons (
    coupon_id SERIAL PRIMARY KEY,
    coupon_code VARCHAR(50) UNIQUE NOT NULL,
    coupon_name VARCHAR(100) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DECIMAL(10, 2) NOT NULL,
    min_order_amount DECIMAL(12, 2) DEFAULT 0 NOT NULL,
    max_discount DECIMAL(10, 2),
    total_quantity INT,
    used_quantity INT DEFAULT 0 NOT NULL,
    valid_from TIMESTAMP NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT TRUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT chk_discount_type CHECK (discount_type IN ('FIXED', 'PERCENT')),
    CONSTRAINT chk_discount_value CHECK (discount_value > 0),
    CONSTRAINT chk_used_quantity CHECK (used_quantity >= 0),
    CONSTRAINT chk_valid_dates CHECK (valid_until > valid_from)
);

COMMENT ON TABLE coupons IS '쿠폰 마스터 (예상: 10만 개)';
COMMENT ON COLUMN coupons.discount_type IS 'FIXED: 정액 할인, PERCENT: 정률 할인';

-- ============================================================================
-- 12. USER_COUPONS (사용자별 쿠폰)
-- ============================================================================
CREATE TABLE user_coupons (
    user_coupon_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id INT NOT NULL,
    is_used BOOLEAN DEFAULT FALSE NOT NULL,
    used_at TIMESTAMP,
    order_id BIGINT,
    issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_user_coupon_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_coupon_coupon FOREIGN KEY (coupon_id) 
        REFERENCES coupons(coupon_id),
    CONSTRAINT fk_user_coupon_order FOREIGN KEY (order_id) 
        REFERENCES orders(order_id),
    CONSTRAINT uk_user_coupon_user_coupon UNIQUE (user_id, coupon_id),
    CONSTRAINT chk_used_logic CHECK (
        (is_used = FALSE AND used_at IS NULL AND order_id IS NULL) OR
        (is_used = TRUE AND used_at IS NOT NULL AND order_id IS NOT NULL)
    )
);

COMMENT ON TABLE user_coupons IS '사용자별 쿠폰 보유/사용 내역 (예상: 5천만 건)';

-- ============================================================================
-- 13. REVIEWS (리뷰) ⭐️ 5천만 건
-- ============================================================================
CREATE TABLE reviews (
    review_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_item_id BIGINT,
    rating INT NOT NULL,
    title VARCHAR(200),
    content TEXT,
    images JSONB,
    helpful_count INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_review_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_review_order_item FOREIGN KEY (order_item_id) 
        REFERENCES order_items(order_item_id),
    CONSTRAINT chk_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_helpful_count CHECK (helpful_count >= 0),
    CONSTRAINT uk_review_user_order_item UNIQUE (user_id, order_item_id)
);

COMMENT ON TABLE reviews IS '⭐️ 상품 리뷰 - 5천만 건';
COMMENT ON COLUMN reviews.images IS 'JSON 배열: ["url1", "url2", ...]';

-- ============================================================================
-- 14. PRODUCT_INVENTORY_HISTORY (재고 변동 이력) ⭐️ 1억 건
-- ============================================================================
CREATE TABLE product_inventory_history (
    history_id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    change_amount INT NOT NULL,
    before_quantity INT NOT NULL,
    after_quantity INT NOT NULL,
    reason VARCHAR(100),
    reference_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by BIGINT,
    
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id),
    CONSTRAINT fk_inventory_created_by FOREIGN KEY (created_by) 
        REFERENCES users(user_id),
    CONSTRAINT chk_change_type CHECK (change_type IN ('IN', 'OUT', 'ADJUST'))
);

COMMENT ON TABLE product_inventory_history IS '⭐️ 재고 변동 이력 - 1억 건 (동시성 제어 테스트용)';
COMMENT ON COLUMN product_inventory_history.change_type IS 'IN: 입고, OUT: 출고, ADJUST: 조정';
COMMENT ON COLUMN product_inventory_history.reason IS 'ORDER, RETURN, STOCK_IN, MANUAL';

-- ============================================================================
-- 15. SEARCH_LOGS (검색 로그) ⭐️ 5천만 건
-- ============================================================================
CREATE TABLE search_logs (
    log_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    search_keyword VARCHAR(200) NOT NULL,
    result_count INT DEFAULT 0 NOT NULL,
    clicked_product_id BIGINT,
    searched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ip_address INET,
    user_agent TEXT,
    
    CONSTRAINT fk_search_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_search_product FOREIGN KEY (clicked_product_id) 
        REFERENCES products(product_id) ON DELETE SET NULL,
    CONSTRAINT chk_result_count CHECK (result_count >= 0)
);

COMMENT ON TABLE search_logs IS '⭐️ 검색 로그 - 5천만 건 (인기 검색어 분석용)';

-- ============================================================================
-- 16. REVIEW_HELPFULS (리뷰 도움이 돼요)
-- ============================================================================
CREATE TABLE review_helpfuls (
    helpful_id BIGSERIAL PRIMARY KEY,
    review_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT fk_helpful_review FOREIGN KEY (review_id)
        REFERENCES reviews(review_id) ON DELETE CASCADE,
    CONSTRAINT fk_helpful_user FOREIGN KEY (user_id)
        REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT uk_helpful_review_user UNIQUE (review_id, user_id)
);

COMMENT ON TABLE review_helpfuls IS '리뷰 도움이 돼요 기록 (사용자당 리뷰당 1회)';

-- ============================================================================
-- 인덱스 생성
-- ============================================================================

-- Review_Helpfuls 인덱스
CREATE INDEX idx_review_helpful_review ON review_helpfuls(review_id);
CREATE INDEX idx_review_helpful_user ON review_helpfuls(user_id);

-- Users 테이블 인덱스
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_tier ON users(tier_id);
CREATE INDEX idx_users_created ON users(created_at);
CREATE INDEX idx_users_total_spent ON users(total_spent DESC);

-- User_Tier_History 인덱스
CREATE INDEX idx_tier_history_user ON user_tier_history(user_id, changed_at DESC);
CREATE INDEX idx_tier_history_date ON user_tier_history(changed_at);

-- Categories 인덱스
CREATE INDEX idx_category_parent ON categories(parent_category_id);
CREATE INDEX idx_category_level ON categories(level, display_order);

-- Products 인덱스
CREATE INDEX idx_product_name_gin ON products USING gin(to_tsvector('simple', product_name));
CREATE INDEX idx_product_category ON products(category_id, is_active, sales_count DESC);
CREATE INDEX idx_product_price ON products(price);
CREATE INDEX idx_product_sales ON products(is_active, sales_count DESC);
CREATE INDEX idx_product_created ON products(is_active, created_at DESC);
CREATE INDEX idx_product_rating ON products(is_active, rating_avg DESC, review_count DESC);
CREATE INDEX idx_product_price_active ON products(is_active, price);
CREATE INDEX idx_product_review_count ON products(is_active, review_count DESC);

-- findDeals 최적화: partial expression index
-- WHERE is_active=true AND original_price > price 조건의 행만 인덱싱
-- ORDER BY (original_price - price) DESC를 인덱스 스캔으로 처리
CREATE INDEX idx_product_deals ON products ((original_price - price) DESC)
    WHERE is_active = true AND original_price IS NOT NULL AND original_price > price;

-- Product_Images 인덱스
CREATE INDEX idx_image_product ON product_images(product_id, image_order);

-- Orders 인덱스
CREATE INDEX idx_order_user ON orders(user_id, order_date DESC);
CREATE INDEX idx_order_status ON orders(order_status, order_date);
CREATE INDEX idx_order_date ON orders(order_date DESC);
CREATE INDEX idx_order_number ON orders(order_number);

-- Order_Items 인덱스 ⭐️ 최적화 핵심
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id, created_at DESC);
CREATE INDEX idx_order_items_created ON order_items(created_at DESC);

-- 커버링 인덱스 (상품별 판매 통계용)
CREATE INDEX idx_order_items_covering 
    ON order_items(product_id, created_at) 
    INCLUDE (quantity, subtotal);

-- Carts 인덱스
CREATE INDEX idx_cart_user ON carts(user_id, updated_at DESC);
CREATE INDEX idx_cart_product ON carts(product_id);

-- Wishlists 인덱스
CREATE INDEX idx_wishlist_user ON wishlists(user_id, created_at DESC);
CREATE INDEX idx_wishlist_product ON wishlists(product_id);

-- Coupons 인덱스
CREATE INDEX idx_coupon_code ON coupons(coupon_code);
CREATE INDEX idx_coupon_valid ON coupons(valid_from, valid_until, is_active);

-- User_Coupons 인덱스
CREATE INDEX idx_user_coupon_user ON user_coupons(user_id, is_used, expires_at);
CREATE INDEX idx_user_coupon_coupon ON user_coupons(coupon_id);

-- Reviews 인덱스 ⭐️
CREATE INDEX idx_review_product ON reviews(product_id, created_at DESC);
CREATE INDEX idx_review_user ON reviews(user_id, created_at DESC);
CREATE INDEX idx_review_rating ON reviews(product_id, rating);
CREATE INDEX idx_review_content_gin ON reviews USING gin(to_tsvector('simple', content));
CREATE UNIQUE INDEX uk_review_user_product_without_order_item
    ON reviews(user_id, product_id)
    WHERE order_item_id IS NULL;

-- Product_Inventory_History 인덱스 ⭐️
CREATE INDEX idx_inventory_product ON product_inventory_history(product_id, created_at DESC);
CREATE INDEX idx_inventory_date ON product_inventory_history(created_at);
CREATE INDEX idx_inventory_type ON product_inventory_history(change_type, created_at);

-- Search_Logs 인덱스 ⭐️
CREATE INDEX idx_search_keyword ON search_logs(search_keyword, searched_at DESC);
CREATE INDEX idx_search_user ON search_logs(user_id, searched_at DESC);
CREATE INDEX idx_search_date ON search_logs(searched_at DESC);

-- ============================================================================
-- 완료 메시지
-- ============================================================================
DO $$
BEGIN
    RAISE NOTICE '====================================================';
    RAISE NOTICE '스키마 생성 완료!';
    RAISE NOTICE '총 16개 테이블, 50+ 인덱스 생성됨';
    RAISE NOTICE '====================================================';
    RAISE NOTICE '다음 단계: 더미 데이터 생성 스크립트 작성';
    RAISE NOTICE '====================================================';
END $$;

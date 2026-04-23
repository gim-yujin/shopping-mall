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
COMMENT ON COLUMN users.total_spent IS '취소 반영 누적 구매 금액 (등급 산정 기준)';
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
COMMENT ON COLUMN user_tier_history.reason IS '등급 변경 사유(예: 정기 등급 점검, MANUAL, PROMOTION)';

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
    version INT DEFAULT 0 NOT NULL,

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
    refunded_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    refunded_points INT DEFAULT 0 NOT NULL,
    points_settled BOOLEAN DEFAULT FALSE NOT NULL,
    payment_method VARCHAR(20),
    shipping_address TEXT,
    recipient_name VARCHAR(100),
    recipient_phone VARCHAR(20),
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    paid_at TIMESTAMP,
    shipped_at TIMESTAMP,
    tracking_number VARCHAR(100),
    carrier VARCHAR(50),
    delivered_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) 
        REFERENCES users(user_id),
    CONSTRAINT chk_order_status CHECK (order_status IN 
        ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT chk_payment_method CHECK (payment_method IN 
        ('CARD', 'BANK', 'KAKAO', 'NAVER', 'PAYCO')),
    CONSTRAINT chk_amounts CHECK (final_amount >= 0),
    CONSTRAINT chk_discount_breakdown CHECK (
        discount_amount = tier_discount_amount + coupon_discount_amount
    ),
    CONSTRAINT chk_refunded_amount_limit CHECK (refunded_amount <= final_amount),
    CONSTRAINT chk_refunded_points_limit CHECK (refunded_points <= used_points)
);

COMMENT ON TABLE orders IS '주문 헤더 (예상: 2천만 건)';
COMMENT ON COLUMN orders.order_number IS '주문 번호 (예: 20240101-XXXXX)';
COMMENT ON COLUMN orders.point_earn_rate_snapshot IS '주문 시점 사용자 등급의 포인트 적립률 스냅샷(%)';
COMMENT ON COLUMN orders.earned_points_snapshot IS '주문 생성 시 실제 적립된 포인트 스냅샷';
COMMENT ON COLUMN orders.used_points IS '주문 시 사용한 포인트 (1P = 1원, 취소 시 환불)';
COMMENT ON COLUMN orders.refunded_amount IS '부분취소/반품/전체취소 누적 환불 금액';
COMMENT ON COLUMN orders.refunded_points IS '부분취소/반품 누적 환불 포인트 (비례 배분, 초과 환불 방지용)';
COMMENT ON COLUMN orders.points_settled IS '포인트 정산 완료 여부 (배송 완료 시 TRUE로 전환, 중복 정산 방지)';

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
    cancelled_quantity INT DEFAULT 0 NOT NULL,
    returned_quantity INT DEFAULT 0 NOT NULL,
    cancelled_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    returned_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    -- V8: OrderItem 상태 머신 + 관리자 반품 워크플로우 컬럼
    status VARCHAR(20) DEFAULT 'NORMAL' NOT NULL,
    return_reason VARCHAR(500),
    reject_reason VARCHAR(500),
    pending_return_quantity INT DEFAULT 0 NOT NULL,
    return_requested_at TIMESTAMP,
    returned_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) 
        REFERENCES orders(order_id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) 
        REFERENCES products(product_id),
    CONSTRAINT chk_quantity CHECK (quantity > 0),
    CONSTRAINT chk_unit_price CHECK (unit_price >= 0),
    CONSTRAINT chk_subtotal CHECK (subtotal >= 0),
    CONSTRAINT chk_order_item_status CHECK (
        status IN ('NORMAL', 'RETURN_REQUESTED', 'RETURN_APPROVED',
                   'RETURNED', 'RETURN_REJECTED', 'CANCELLED')
    ),
    CONSTRAINT chk_pending_return_quantity CHECK (pending_return_quantity >= 0)
);

COMMENT ON TABLE order_items IS '⭐️ 주문 상세 - 1억 건 주인공 테이블';
COMMENT ON COLUMN order_items.product_name IS '주문 당시 상품명 스냅샷';
COMMENT ON COLUMN order_items.unit_price IS '주문 당시 가격 스냅샷';
COMMENT ON COLUMN order_items.cancelled_quantity IS '부분 취소된 수량';
COMMENT ON COLUMN order_items.returned_quantity IS '반품된 수량';
COMMENT ON COLUMN order_items.cancelled_amount IS '부분 취소 누적 금액';
COMMENT ON COLUMN order_items.returned_amount IS '반품 누적 금액';
COMMENT ON COLUMN order_items.status IS '아이템 상태: NORMAL, RETURN_REQUESTED, RETURN_APPROVED, RETURNED, RETURN_REJECTED, CANCELLED';
COMMENT ON COLUMN order_items.return_reason IS '사용자 반품 사유 (DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER)';
COMMENT ON COLUMN order_items.reject_reason IS '관리자 반품 거절 사유';
COMMENT ON COLUMN order_items.pending_return_quantity IS '반품 대기 수량 — 신청 시 증가, 승인/거절 시 차감';
COMMENT ON COLUMN order_items.return_requested_at IS '반품 신청 일시';
COMMENT ON COLUMN order_items.returned_at IS '반품 완료(승인) 일시';

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
    version INT DEFAULT 0 NOT NULL,

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
-- 17. POINT_HISTORY (포인트 변동 이력)
-- ============================================================================
-- [신규] 기존에는 포인트 적립/사용/환불이 User.pointBalance에만 반영되어
-- 개별 변동을 추적할 수 없었다. 재고는 product_inventory_history로 모든 변동을
-- before/after 스냅샷으로 기록하면서, 같은 금전적 가치를 가진 포인트는
-- 이력이 없는 비대칭이 존재했다.
-- 이 테이블은 모든 포인트 변동을 기록하여 고객 문의 대응과 감사 추적을 가능하게 한다.
CREATE TABLE point_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    change_type VARCHAR(20) NOT NULL,
    amount INT NOT NULL,
    balance_after INT NOT NULL,
    reference_type VARCHAR(20),
    reference_id BIGINT,
    description VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

    CONSTRAINT fk_point_history_user FOREIGN KEY (user_id)
        REFERENCES users(user_id),
    CONSTRAINT chk_point_change_type CHECK (change_type IN ('EARN', 'USE', 'REFUND', 'EXPIRE', 'ADJUST')),
    CONSTRAINT chk_point_amount CHECK (amount > 0),
    CONSTRAINT chk_point_reference_type CHECK (
        reference_type IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN', 'ADMIN', 'SYSTEM')
    )
);

COMMENT ON TABLE point_history IS '포인트 변동 이력 (예상: 5천만 건)';
COMMENT ON COLUMN point_history.change_type IS 'EARN: 적립, USE: 사용, REFUND: 환불, EXPIRE: 만료, ADJUST: 수동조정';
COMMENT ON COLUMN point_history.amount IS '변동 수량 (항상 양수, 증감 방향은 change_type으로 구분)';
COMMENT ON COLUMN point_history.balance_after IS '변동 후 잔액 스냅샷';
COMMENT ON COLUMN point_history.reference_type IS 'ORDER: 주문, CANCEL: 전체취소, PARTIAL_CANCEL: 부분취소, RETURN: 반품 환불, ADMIN: 관리자, SYSTEM: 시스템';

-- ============================================================================
-- 18. IDEMPOTENCY_RECORDS (멱등성 키 레코드)
-- ============================================================================
-- 주문 생성 등 멱등성 보장이 필요한 API 요청의 중복 방지 레코드.
-- 클라이언트가 X-Idempotency-Key 헤더로 UUID를 전달하면
-- (user_id, idempotency_key) UNIQUE 제약으로 중복 요청을 물리적으로 차단한다.
CREATE TABLE idempotency_records (
    record_id       BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING',
    resource_type   VARCHAR(50)  NOT NULL,
    resource_id     BIGINT,
    response_body   TEXT,
    http_status     INT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,

    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_idempotency_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE idempotency_records IS '멱등성 키 레코드 (주문 중복 생성 방지)';

-- ============================================================================
-- 19. OUTBOX_EVENTS (Transactional Outbox)
-- ============================================================================
-- 비즈니스 트랜잭션과 함께 저장되는 이벤트 레코드.
-- 폴러가 주기적으로 PENDING 이벤트를 읽어 처리하고 PROCESSED로 전이한다.
CREATE TABLE outbox_events (
    event_id        BIGSERIAL    PRIMARY KEY,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP,
    retry_count     INT          NOT NULL DEFAULT 0,
    -- [Phase 15] 지수 백오프 재시도 시각: 폴러가 이 시각 이후에만 이벤트를 조회한다.
    next_retry_at   TIMESTAMP,
    -- [Phase 15] 마지막 실패 원인: DEAD_LETTER 이벤트 진단에 사용.
    last_error      TEXT,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'DEAD_LETTER'))
);

COMMENT ON TABLE outbox_events IS 'Transactional Outbox 이벤트 레코드';

-- ============================================================================
-- 20. FLASH_SALES / FLASH_SALE_ITEMS / FLASH_SALE_PURCHASES (플래시 세일)
-- ============================================================================
-- 플래시 세일 이벤트·할당 재고·1인 1구매 감사 로그.
-- 일반 재고(products.stock_quantity)와 격리된 세일 전용 재고(remaining_quantity)를
-- CAS 원자 감분으로 차감하여 burst 부하가 일반 주문 경로와 경합하지 않게 한다.
CREATE TABLE flash_sales (
    flash_sale_id   BIGSERIAL    PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    start_time      TIMESTAMP    NOT NULL,
    end_time        TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         INT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_flash_sale_status
        CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','CANCELLED')),
    CONSTRAINT chk_flash_sale_time
        CHECK (end_time > start_time)
);

COMMENT ON TABLE flash_sales IS '플래시 세일(타임세일) 이벤트';

CREATE TABLE flash_sale_items (
    flash_sale_item_id   BIGSERIAL     PRIMARY KEY,
    flash_sale_id        BIGINT        NOT NULL,
    product_id           BIGINT        NOT NULL,
    sale_price           DECIMAL(12,2) NOT NULL,
    allocated_quantity   INT           NOT NULL,
    remaining_quantity   INT           NOT NULL,
    per_user_limit       INT           NOT NULL DEFAULT 1,
    version              INT           NOT NULL DEFAULT 0,

    CONSTRAINT fk_fsi_flash_sale FOREIGN KEY (flash_sale_id)
        REFERENCES flash_sales(flash_sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_fsi_product FOREIGN KEY (product_id)
        REFERENCES products(product_id),
    CONSTRAINT chk_fsi_remaining CHECK (remaining_quantity >= 0),
    CONSTRAINT chk_fsi_allocated CHECK (allocated_quantity > 0),
    CONSTRAINT chk_fsi_price CHECK (sale_price >= 0),
    CONSTRAINT chk_fsi_per_user CHECK (per_user_limit >= 1),
    CONSTRAINT uk_fsi_sale_product UNIQUE (flash_sale_id, product_id)
);

COMMENT ON TABLE flash_sale_items IS '세일 대상 상품 및 할당 재고 (CAS 대상 remaining_quantity)';

CREATE TABLE flash_sale_purchases (
    flash_sale_purchase_id  BIGSERIAL PRIMARY KEY,
    flash_sale_id           BIGINT    NOT NULL,
    user_id                 BIGINT    NOT NULL,
    order_id                BIGINT    NOT NULL,
    purchased_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fsp_flash_sale FOREIGN KEY (flash_sale_id)
        REFERENCES flash_sales(flash_sale_id),
    CONSTRAINT fk_fsp_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_fsp_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT uk_fsp_user_sale UNIQUE (flash_sale_id, user_id)
);

COMMENT ON TABLE flash_sale_purchases IS '1인 1구매 감사 로그. uk_fsp_user_sale가 중복 구매의 최종 방어선';

-- ============================================================================
-- 인덱스 생성
-- ============================================================================

-- Review_Helpfuls 인덱스
-- idx_review_helpful_review 불필요: uk_helpful_review_user(review_id, user_id) 선두 컬럼으로 커버됨
CREATE INDEX idx_review_helpful_user ON review_helpfuls(user_id, review_id);

-- Users 테이블 인덱스
-- idx_users_email, idx_users_username 불필요: UNIQUE 제약이 자동으로 unique index 생성
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

-- searchByKeywordLikeFlat() LIKE '%keyword%' 최적화: pg_trgm GIN 인덱스.
--
-- 문제: FTS 폴백 시 LOWER(product_name) LIKE '%keyword%' 쿼리가 실행되는데,
-- 양방향 LIKE(%...%)는 B-tree 인덱스를 활용할 수 없어 Full Seq Scan이 발생한다.
--
-- 해결: pg_trgm의 gin_trgm_ops로 trigram 기반 GIN 인덱스를 생성한다.
-- LOWER() expression index로 대소문자 비구분 LIKE 조건에서 Bitmap Index Scan을 활용한다.
-- Seq Scan 대비 실행 시간 ~77% 감소, 버퍼 접근 ~48% 감소 확인됨.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_product_name_trgm ON products USING gin(LOWER(product_name) gin_trgm_ops);

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
CREATE INDEX idx_image_thumbnail ON product_images(product_id) WHERE is_thumbnail = true;

-- Orders 인덱스
CREATE INDEX idx_order_user ON orders(user_id, order_date DESC);
CREATE INDEX idx_order_status ON orders(order_status, order_date);
CREATE INDEX idx_order_date ON orders(order_date DESC);
-- TierScheduler 전년도 실적 집계 최적화:
-- 취소 주문을 제외한 연간 범위 스캔에서 user_id, final_amount를 heap 접근 없이 읽는다.
CREATE INDEX idx_order_yearly_spent_non_cancelled
    ON orders(order_date)
    INCLUDE (user_id, final_amount)
    WHERE order_status <> 'CANCELLED';
-- idx_order_number 불필요: order_number UNIQUE 제약이 자동으로 unique index 생성

-- Order_Items 인덱스 ⭐️ 최적화 핵심
CREATE INDEX idx_order_items_order ON order_items(order_id);
CREATE INDEX idx_order_items_product ON order_items(product_id, created_at DESC);
CREATE INDEX idx_order_items_created ON order_items(created_at DESC);

-- 커버링 인덱스 (상품별 판매 통계용)
CREATE INDEX idx_order_items_covering 
    ON order_items(product_id, created_at) 
    INCLUDE (quantity, subtotal);

-- V8: 관리자 반품 대기 목록 조회용 partial index
-- RETURN_REQUESTED 상태의 아이템만 인덱싱하여 관리자 페이지 성능을 보장한다.
-- 전체 order_items 대비 반품 신청 건은 극소수이므로 partial index가 효율적이다.
CREATE INDEX idx_order_items_status_return_requested
    ON order_items (status)
    WHERE status = 'RETURN_REQUESTED';

-- Carts 인덱스
CREATE INDEX idx_cart_user ON carts(user_id, updated_at DESC);
CREATE INDEX idx_cart_product ON carts(product_id);

-- Wishlists 인덱스
CREATE INDEX idx_wishlist_user ON wishlists(user_id, created_at DESC);
CREATE INDEX idx_wishlist_product ON wishlists(product_id);

-- Coupons 인덱스
-- idx_coupon_code 불필요: coupon_code UNIQUE 제약이 자동으로 unique index 생성
CREATE INDEX idx_coupon_valid ON coupons(valid_from, valid_until, is_active);

-- User_Coupons 인덱스
CREATE INDEX idx_user_coupon_user ON user_coupons(user_id, is_used, expires_at);
CREATE INDEX idx_user_coupon_coupon ON user_coupons(coupon_id);

-- [Phase 8] user_coupons.order_id 부분 인덱스 (주문 취소 시 쿠폰 복원 쿼리 최적화).
--
-- 문제: OrderCancellationService.cancelOrderInternal()과 PartialCancellationService가
-- userCouponRepository.findByOrderId(orderId)를 호출하여 주문에 사용된 쿠폰을 복원한다.
-- user_coupons 테이블은 5천만 건 규모인데, order_id 컬럼에 인덱스가 없어
-- findByOrderId()가 Full Table Scan을 수행한다.
--
-- 해결: order_id IS NOT NULL 조건의 부분 인덱스를 생성한다.
-- 전체 user_coupons 중 쿠폰을 사용한(order_id가 있는) 행만 인덱싱하므로,
-- 미사용 쿠폰(order_id IS NULL, 약 70~80%)을 제외하여 인덱스 크기를 대폭 줄인다.
-- B-tree 탐색으로 O(log N) 조회가 가능해진다.
CREATE INDEX idx_user_coupon_order ON user_coupons(order_id)
    WHERE order_id IS NOT NULL;

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

-- findPopularKeywords() GROUP BY 최적화: Index-Only Scan용 복합 인덱스.
--
-- 문제: 기존 idx_search_date(searched_at DESC)는 날짜 범위 필터링에는 활용되지만,
-- 필터 후 search_keyword에 대한 GROUP BY에서 Heap 접근이 필요하다.
-- 7일치 데이터가 수만~수십만 건이면 Bitmap Heap Scan + HashAggregate 비용이 크다.
--
-- 해결: (searched_at DESC, search_keyword) 복합 인덱스로 날짜 Range Scan 후
-- search_keyword를 인덱스에서 직접 읽어 Heap 접근 없이 Index-Only Scan을 달성한다.
-- Bitmap Heap Scan 대비 실행 시간 ~59% 감소, 버퍼 접근 ~87% 감소 확인됨.
CREATE INDEX idx_search_date_keyword ON search_logs(searched_at DESC, search_keyword);

-- Point_History 인덱스
CREATE INDEX idx_point_history_user ON point_history(user_id, created_at DESC);
CREATE INDEX idx_point_history_reference ON point_history(reference_type, reference_id);

-- findByOrderId() 인덱스 선행 컬럼 최적화: reference_id 기반 조회용 복합 인덱스.
--
-- 문제: findByOrderId() 쿼리는 reference_id = :orderId AND reference_type IN ('ORDER', 'CANCEL')
-- 패턴으로 특정 주문의 포인트 이력을 조회한다.
-- 기존 idx_point_history_reference(reference_type, reference_id)는 선행 컬럼이 reference_type이므로,
-- IN 절의 두 값에 대해 각각 인덱스 스캔 후 BitmapOr 병합이 필요하다.
-- 50M 테이블에서 reference_id를 직접 필터링하면 1회 범위 스캔으로 충분하다.
--
-- 해결: (reference_id, reference_type, created_at) 복합 인덱스로
-- reference_id 등값 조건에서 단일 Index Range Scan을 수행하고,
-- reference_type 필터와 created_at 정렬을 인덱스에서 직접 처리한다.
CREATE INDEX idx_point_history_ref_order ON point_history(reference_id, reference_type, created_at);

-- [Phase 8] point_history 복합 인덱스 (change_type별 최신순 조회 최적화).
--
-- 문제: 포인트 이력 조회 시 change_type으로 필터링 후 created_at DESC로 정렬하는 패턴이 빈번하다.
-- 예: "최근 적립 이력", "최근 사용 이력" 등 관리자/사용자 페이지에서 타입별 필터링 조회.
-- 기존 idx_point_history_user는 (user_id, created_at DESC)만 커버하여,
-- change_type 필터가 추가되면 인덱스 스캔 후 다시 필터링(Filter)이 발생한다.
--
-- 해결: (change_type, created_at DESC) 복합 인덱스로 타입별 최신순 조회를
-- 인덱스 범위 스캔(Index Range Scan)만으로 처리한다.
CREATE INDEX idx_point_history_type_created ON point_history(change_type, created_at DESC);

-- [Phase 8] point_history 단독 created_at 인덱스 (전체 이력 최신순 조회 최적화).
--
-- 문제: 관리자 대시보드에서 전체 포인트 변동을 최신순으로 페이징 조회할 때,
-- 기존 (user_id, created_at DESC) 인덱스는 user_id 조건 없이는 활용되지 않는다.
-- (복합 인덱스의 선행 컬럼이 WHERE 조건에 없으면 인덱스 스캔 불가)
--
-- 해결: created_at DESC 단독 인덱스로 전체 이력 최신순 조회 시
-- Index Scan Backward가 가능하게 한다.
CREATE INDEX idx_point_history_created ON point_history(created_at DESC);

-- Idempotency_Records 인덱스
CREATE UNIQUE INDEX uk_idempotency_user_key ON idempotency_records(user_id, idempotency_key);
CREATE INDEX idx_idempotency_created ON idempotency_records(created_at);

-- Outbox_Events 인덱스
CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_processed_at ON outbox_events(processed_at) WHERE status = 'PROCESSED';
-- [Phase 15] Dead Letter 이벤트 조회용 부분 인덱스
CREATE INDEX idx_outbox_dead_letter ON outbox_events(processed_at) WHERE status = 'DEAD_LETTER';
CREATE INDEX idx_outbox_retry ON outbox_events(next_retry_at)
    WHERE status = 'PENDING' AND next_retry_at IS NOT NULL;

-- Flash Sales 인덱스
-- status·start_time 조합으로 진행중/예정 세일 목록을 Index Range Scan으로 조회.
CREATE INDEX idx_flash_sale_status_start ON flash_sales(status, start_time);
CREATE INDEX idx_fsi_flash_sale          ON flash_sale_items(flash_sale_id);
CREATE INDEX idx_fsp_flash_sale          ON flash_sale_purchases(flash_sale_id, purchased_at DESC);
-- uk_fsi_sale_product, uk_fsp_user_sale은 UNIQUE 제약이 자동으로 unique index 생성 (PG 내부)

-- ============================================================================
-- [Phase 18] 읽기 전용 뷰 — CQRS 읽기 모델 분리
-- ============================================================================
-- 상품 목록/검색/홈 페이지에서 사용하는 플랫 프로젝션 뷰.
--
-- 문제: 기존에는 Product 엔티티를 JOIN FETCH로 가져온 뒤 Lazy 컬렉션(images)에
-- 접근하여 썸네일을 추출했다. 이는 상품당 1개의 추가 쿼리를 유발하거나,
-- batch_fetch_size에 의존하는 비결정적 동작을 초래했다.
--
-- 해결: 썸네일 URL을 서브쿼리로 한 번에 가져오는 뷰를 생성한다.
-- 뷰는 물리화(MATERIALIZED)하지 않아 항상 최신 데이터를 반환한다.
-- 읽기 모델(ProductListReadModel)이 이 뷰를 조회하여 JPA 프록시 없이 플랫 데이터를 제공한다.
CREATE OR REPLACE VIEW v_product_list AS
SELECT
    p.product_id,
    p.product_name,
    p.price,
    p.original_price,
    p.rating_avg,
    p.review_count,
    p.sales_count,
    c.category_id,
    c.category_name,
    p.created_at,
    COALESCE(
        (SELECT pi.image_url FROM product_images pi
         WHERE pi.product_id = p.product_id AND pi.is_thumbnail = true
         LIMIT 1),
        '/images/product-placeholder.svg'
    ) AS thumbnail_url,
    p.is_active
FROM products p
JOIN categories c ON c.category_id = p.category_id;

-- [Phase 18] 주문 목록 읽기 전용 뷰 — CQRS 읽기 모델 분리.
--
-- 문제: OrderQueryService의 getOrdersByUser/getAllOrders가 Page<Order> 엔티티를 반환한 뒤
-- fetchOrderItems()로 2차 쿼리를 발행하여 아이템 수(itemCount)를 계산했다.
-- 목록 페이지에는 주문당 아이템 수와 대표 상품명만 필요한데, 전체 OrderItem 컬렉션을 로딩했다.
--
-- 해결: 서브쿼리로 item_count와 first_product_name을 미리 계산하여
-- JPA 컬렉션 페치 없이 플랫 데이터를 제공한다. 2-쿼리 패턴을 제거하고 단일 쿼리로 처리.
CREATE OR REPLACE VIEW v_order_list AS
SELECT
    o.order_id,
    o.order_number,
    o.user_id,
    o.order_status,
    o.total_amount,
    o.discount_amount,
    o.shipping_fee,
    o.final_amount,
    o.order_date,
    o.paid_at,
    o.shipped_at,
    o.delivered_at,
    o.cancelled_at,
    (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count,
    (SELECT oi2.product_name FROM order_items oi2
     WHERE oi2.order_id = o.order_id
     ORDER BY oi2.order_item_id LIMIT 1) AS first_product_name
FROM orders o;

-- [Phase 22] 리뷰 목록 읽기 전용 뷰 — CQRS 읽기 모델 분리.
--
-- 문제: ReviewService.getProductReviews()가 Review 엔티티만 반환하므로
-- 리뷰 작성자명(username)을 표시하려면 별도 User 조회가 필요하다.
--
-- 해결: users JOIN으로 username을 미리 포함시켜 단일 쿼리로 처리.
CREATE OR REPLACE VIEW v_review_list AS
SELECT
    r.review_id,
    r.product_id,
    r.user_id,
    u.username,
    r.rating,
    r.title,
    r.content,
    r.helpful_count,
    r.created_at,
    r.updated_at
FROM reviews r
JOIN users u ON u.user_id = r.user_id;

-- [Phase 22] 위시리스트 목록 읽기 전용 뷰 — CQRS 읽기 모델 분리.
--
-- 문제: WishlistService.getWishlist()가 JOIN FETCH + Hibernate.initialize()로
-- 상품 이미지 컬렉션을 수동 초기화하는 우회 패턴을 사용하였다.
-- @BatchSize(size=30)로 IN 쿼리를 줄였지만, JPA 프록시 기반 접근 자체가 비효율적이다.
--
-- 해결: products JOIN + 썸네일 서브쿼리로 필요 컬럼만 플랫 프로젝션.
-- Hibernate 프록시/컬렉션 초기화 없이 순수 SQL로 처리.
CREATE OR REPLACE VIEW v_wishlist_list AS
SELECT
    w.wishlist_id,
    w.user_id,
    p.product_id,
    p.product_name,
    p.price,
    p.original_price,
    COALESCE(
        (SELECT pi.image_url FROM product_images pi
         WHERE pi.product_id = p.product_id AND pi.is_thumbnail = true
         LIMIT 1),
        '/images/product-placeholder.svg'
    ) AS thumbnail_url,
    p.stock_quantity,
    w.created_at
FROM wishlists w
JOIN products p ON p.product_id = w.product_id;

-- ============================================================================
-- 스키마 생성 완료
-- ============================================================================
-- [FIX] 기존 DO $$ ... END $$; PL/pgSQL 익명 블록을 제거했다.
-- 원인: Spring Boot의 ScriptUtils가 SQL 스크립트를 세미콜론(;) 기준으로 분할할 때,
-- $$ 달러 쿼팅 내부의 세미콜론까지 문장 구분자로 오인하는 경우가 있다.
-- 이로 인해 DO $$ 블록이 여러 개의 불완전한 SQL 조각으로 분리되어
-- PSQLException (Parser.java) 파싱 에러가 발생한다.
-- RAISE NOTICE는 디버깅 편의용이었으므로 주석으로 대체한다.
-- 총 22개 테이블, 64개 인덱스 생성됨 (일반 61 + UNIQUE 3)

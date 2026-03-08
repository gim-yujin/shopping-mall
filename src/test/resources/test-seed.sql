-- =============================================================================
-- 테스트 시드 데이터
-- =============================================================================
-- [FIX] 기존 test-seed.sql에는 user_tiers와 categories만 존재했다.
-- 대부분의 통합 테스트(@SpringBootTest)는 @BeforeEach에서
-- "SELECT ... FROM users WHERE is_active = true" 등으로 기존 데이터를 조회하는데,
-- 사용자/상품 시드가 없으면 EmptyResultDataAccessException이 발생해
-- ApplicationContext 초기화 이후 모든 테스트가 연쇄 실패한다.
--
-- 이 파일은 모든 통합 테스트가 공통으로 필요로 하는 최소 기준 데이터를 제공한다.
-- 개별 테스트가 TestDataFactory로 자체 생성하는 데이터와 ID 충돌을 피하기 위해
-- 높은 ID 범위(9000+)를 사용한다.
-- =============================================================================

-- ===================== 1. 회원 등급 =====================
INSERT INTO user_tiers (
    tier_id, tier_name, tier_level, min_spent,
    discount_rate, point_earn_rate, free_shipping_threshold,
    description, created_at
)
VALUES
    -- [FIX] BASIC 등급의 free_shipping_threshold를 NULL → 100000으로 변경.
    -- 원인: ShippingFeeCalculator.calculateShippingFee()에서 tier.getFreeShippingThreshold()가
    -- null을 반환하면 .compareTo() 호출 시 NPE가 발생한다.
    -- 또한 OrderServiceIntegrationTest(라인 535)의 테스트 코드에서도
    -- SQL로 조회한 free_shipping_threshold가 null이면 동일한 NPE가 발생한다.
    -- 비즈니스 의미: BASIC 등급은 10만원 이상 주문 시 무료배송.
    (1, 'BASIC', 1, 0, 0, 1.00, 100000, '기본 등급', NOW()),
    (2, 'SILVER', 2, 100000, 1, 1.20, 50000, '실버 등급', NOW())
ON CONFLICT (tier_id) DO UPDATE SET
    tier_name = EXCLUDED.tier_name,
    tier_level = EXCLUDED.tier_level,
    min_spent = EXCLUDED.min_spent,
    discount_rate = EXCLUDED.discount_rate,
    point_earn_rate = EXCLUDED.point_earn_rate,
    free_shipping_threshold = EXCLUDED.free_shipping_threshold,
    description = EXCLUDED.description;

SELECT setval(
    pg_get_serial_sequence('user_tiers', 'tier_id'),
    GREATEST((SELECT COALESCE(MAX(tier_id), 1) FROM user_tiers), 1),
    TRUE
)
WHERE pg_get_serial_sequence('user_tiers', 'tier_id') IS NOT NULL;

-- ===================== 2. 카테고리 =====================
INSERT INTO categories (
    category_id, category_name, parent_category_id,
    level, display_order, is_active, created_at
)
VALUES
    (1000, 'TEST_ROOT_CATEGORY', NULL, 1, 1, TRUE, NOW()),
    (1001, 'TEST_CHILD_CATEGORY', 1000, 2, 1, TRUE, NOW()),
    (1002, 'TEST_LEAF_CATEGORY', 1001, 3, 1, TRUE, NOW())
ON CONFLICT (category_id) DO UPDATE SET
    category_name = EXCLUDED.category_name,
    parent_category_id = EXCLUDED.parent_category_id,
    level = EXCLUDED.level,
    display_order = EXCLUDED.display_order,
    is_active = EXCLUDED.is_active;

SELECT setval(
    pg_get_serial_sequence('categories', 'category_id'),
    GREATEST((SELECT COALESCE(MAX(category_id), 1) FROM categories), 1),
    TRUE
)
WHERE pg_get_serial_sequence('categories', 'category_id') IS NOT NULL;

-- ===================== 3. 사용자 =====================
-- [FIX] 통합 테스트가 요구하는 사용자 데이터 추가:
--   - CartServiceIntegrationTest: "SELECT FROM users WHERE is_active=true AND role='ROLE_USER' AND NOT EXISTS(carts)"
--   - OrderServiceIntegrationTest: 동일 패턴
--   - ReviewServiceIntegrationTest: "LIMIT 2" — 최소 2명 필요
--   - UserServiceIntegrationTest: email, name, phone, password_hash 조회
--   - CouponServiceIntegrationTest: 쿠폰이 없는 사용자
--   - ReviewServiceIntegrationTest 라인 532: "user_id != ? ... LIMIT 3" — 최소 4명 필요
--
-- BCrypt 해시: "Test1234!"를 bcrypt(rounds=10)으로 해싱한 값.
-- phone 패턴: UserService.PHONE_PATTERN = "^$|^01[0-9]-?\d{3,4}-?\d{4}$"
INSERT INTO users (
    user_id, username, email, password_hash, name, phone,
    role, tier_id, total_spent, point_balance,
    is_active, created_at, updated_at
)
VALUES
    (9001, 'test_user_a', 'test_a@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트A', '010-1234-5678',
     'ROLE_USER', 1, 0, 1000, TRUE, NOW(), NOW()),

    (9002, 'test_user_b', 'test_b@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트B', '010-2345-6789',
     'ROLE_USER', 1, 0, 500, TRUE, NOW(), NOW()),

    (9003, 'test_user_c', 'test_c@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트C', '010-3456-7890',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9004, 'test_user_d', 'test_d@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트D', '010-4567-8901',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9005, 'test_user_e', 'test_e@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트E', '010-5678-9012',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    -- [FIX] OrderOversellingTest는 10명의 ROLE_USER가 동시에 주문해야 한다.
    -- 기존 5명(9001~9005)으로는 부족해 RuntimeException이 발생했다.
    -- 추가 10명(9007~9016)을 삽입해 여유 있게 15명을 확보한다.
    (9007, 'test_user_g', 'test_g@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트G', '010-7777-0001',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9008, 'test_user_h', 'test_h@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트H', '010-7777-0002',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9009, 'test_user_i', 'test_i@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트I', '010-7777-0003',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9010, 'test_user_j', 'test_j@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트J', '010-7777-0004',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9011, 'test_user_k', 'test_k@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트K', '010-7777-0005',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9012, 'test_user_l', 'test_l@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트L', '010-7777-0006',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9013, 'test_user_m', 'test_m@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트M', '010-7777-0007',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9014, 'test_user_n', 'test_n@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트N', '010-7777-0008',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9015, 'test_user_o', 'test_o@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트O', '010-7777-0009',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    (9016, 'test_user_p', 'test_p@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '테스트P', '010-7777-0010',
     'ROLE_USER', 1, 0, 0, TRUE, NOW(), NOW()),

    -- 관리자 계정 (AdminReturnManagementIntegrationTest 등에서 필요할 수 있음)
    (9006, 'test_admin', 'test_admin@example.com',
     '$2b$10$yand.RRKtoh8L4rsWJv4xeM6T8771FwNQpJrpQpI6LIEdhvgNlGQy',
     '관리자', '010-9999-9999',
     'ROLE_ADMIN', 1, 0, 0, TRUE, NOW(), NOW())
ON CONFLICT (user_id) DO NOTHING;

-- 시퀀스를 현재 최대값으로 보정 — TestDataFactory가 INSERT RETURNING으로
-- 새 user_id를 발급받을 때 시드 ID(9001~9006)와 충돌하지 않도록 한다.
SELECT setval(
    pg_get_serial_sequence('users', 'user_id'),
    GREATEST((SELECT COALESCE(MAX(user_id), 1) FROM users), 1),
    TRUE
)
WHERE pg_get_serial_sequence('users', 'user_id') IS NOT NULL;

-- ===================== 4. 상품 =====================
-- [FIX] 통합 테스트가 요구하는 상품 데이터 추가:
--   - CartServiceIntegrationTest: "stock_quantity >= 50 LIMIT 2" — 최소 2개 (재고 50+)
--   - OrderServiceIntegrationTest: "stock_quantity >= 100 LIMIT 1" — 최소 1개 (재고 100+)
--   - PartialCancellationIntegrationTest: "stock_quantity >= 100 LIMIT 2" — 최소 2개
--   - CartServiceIntegrationTest (MAX_CART_ITEMS): 50개 활성 상품 + 추가 1개 — 최소 51개
--   - ProductServiceIntegrationTestSupplementary: "original_price > price" 딜 상품 필요
--   - CartServiceIntegrationTest: "is_active = false" 비활성 상품 1개 필요
--
-- 전략: 활성 상품 55개(stock=500) + 비활성 1개 = 총 56개
-- 일부 상품에 original_price > price를 설정해 딜(할인) 테스트를 지원한다.
-- 카테고리는 seed에서 생성한 1002(리프)를 사용한다.

-- 활성 상품 55개 (product_id 9001 ~ 9055)
-- 처음 5개는 original_price > price (딜 상품), 나머지는 original_price = price
INSERT INTO products (
    product_id, product_name, category_id, description,
    price, original_price, stock_quantity, sales_count,
    view_count, rating_avg, review_count,
    is_active, created_at, updated_at
)
VALUES
    -- 딜(할인) 상품 5개: original_price > price
    (9001, '테스트 상품 01 (할인)', 1002, '통합 테스트용 시드 상품', 8000.00, 12000.00, 500, 10, 100, 0, 0, TRUE, NOW(), NOW()),
    (9002, '테스트 상품 02 (할인)', 1002, '통합 테스트용 시드 상품', 15000.00, 20000.00, 500, 20, 200, 0, 0, TRUE, NOW(), NOW()),
    (9003, '테스트 상품 03 (할인)', 1002, '통합 테스트용 시드 상품', 25000.00, 35000.00, 500, 5, 50, 0, 0, TRUE, NOW(), NOW()),
    (9004, '테스트 상품 04 (할인)', 1002, '통합 테스트용 시드 상품', 9900.00, 15000.00, 500, 8, 80, 0, 0, TRUE, NOW(), NOW()),
    (9005, '테스트 상품 05 (할인)', 1002, '통합 테스트용 시드 상품', 45000.00, 60000.00, 500, 30, 300, 0, 0, TRUE, NOW(), NOW()),
    -- 일반 상품 50개
    (9006, '테스트 상품 06', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9007, '테스트 상품 07', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9008, '테스트 상품 08', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9009, '테스트 상품 09', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9010, '테스트 상품 10', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9011, '테스트 상품 11', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9012, '테스트 상품 12', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9013, '테스트 상품 13', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9014, '테스트 상품 14', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9015, '테스트 상품 15', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9016, '테스트 상품 16', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9017, '테스트 상품 17', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9018, '테스트 상품 18', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9019, '테스트 상품 19', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9020, '테스트 상품 20', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9021, '테스트 상품 21', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9022, '테스트 상품 22', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9023, '테스트 상품 23', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9024, '테스트 상품 24', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9025, '테스트 상품 25', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9026, '테스트 상품 26', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9027, '테스트 상품 27', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9028, '테스트 상품 28', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9029, '테스트 상품 29', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9030, '테스트 상품 30', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9031, '테스트 상품 31', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9032, '테스트 상품 32', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9033, '테스트 상품 33', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9034, '테스트 상품 34', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9035, '테스트 상품 35', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9036, '테스트 상품 36', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9037, '테스트 상품 37', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9038, '테스트 상품 38', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9039, '테스트 상품 39', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9040, '테스트 상품 40', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9041, '테스트 상품 41', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9042, '테스트 상품 42', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9043, '테스트 상품 43', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9044, '테스트 상품 44', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9045, '테스트 상품 45', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9046, '테스트 상품 46', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9047, '테스트 상품 47', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9048, '테스트 상품 48', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9049, '테스트 상품 49', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9050, '테스트 상품 50', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9051, '테스트 상품 51', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9052, '테스트 상품 52', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9053, '테스트 상품 53', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9054, '테스트 상품 54', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),
    (9055, '테스트 상품 55', 1002, '통합 테스트용 시드 상품', 10000.00, 10000.00, 500, 0, 0, 0, 0, TRUE, NOW(), NOW()),

    -- 비활성 상품 1개 — CartServiceIntegrationTest의 "비활성 상품 추가 시도" 테스트용
    (9056, '테스트 상품 (비활성)', 1002, '비활성 테스트용', 10000.00, 10000.00, 100, 0, 0, 0, 0, FALSE, NOW(), NOW())
ON CONFLICT (product_id) DO NOTHING;

-- 시퀀스 보정 — TestDataFactory.createActiveProduct()가 RETURNING으로
-- product_id를 발급받을 때 시드 ID와 충돌 방지
SELECT setval(
    pg_get_serial_sequence('products', 'product_id'),
    GREATEST((SELECT COALESCE(MAX(product_id), 1) FROM products), 1),
    TRUE
)
WHERE pg_get_serial_sequence('products', 'product_id') IS NOT NULL;

-- ===================== 5. 리뷰 =====================
-- [FIX] ReviewHelpfulConcurrencyTest가 요구하는 리뷰 데이터 추가.
-- setUp에서 "SELECT review_id FROM reviews WHERE helpful_count = 0 LIMIT 1"로
-- 리뷰를 조회하는데, reviews 테이블이 비어 있으면 EmptyResultDataAccessException이 발생한다.
-- order_item_id는 NULL — 구매 인증 없는 리뷰 (스키마가 nullable 허용).
-- user_id=9002가 product_id=9001에 대해 작성한 리뷰로 설정한다.
INSERT INTO reviews (
    product_id, user_id, order_item_id, rating, title, content,
    helpful_count, created_at, updated_at
)
VALUES
    (9001, 9002, NULL, 4, '테스트 리뷰 제목', '동시성 테스트용 시드 리뷰 내용입니다.', 0, NOW(), NOW())
ON CONFLICT DO NOTHING;

-- products.review_count/rating_avg 동기화 — 리뷰 삽입 후 상품 통계를 맞춘다.
UPDATE products SET review_count = 1, rating_avg = 4.00 WHERE product_id = 9001;

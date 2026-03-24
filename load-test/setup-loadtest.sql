-- ============================================================================
-- 부하 테스트용 데이터 준비 스크립트
-- 실행: psql -U postgres -d shopping_mall_db -f setup-loadtest.sql
-- ============================================================================

-- 1) 부하 테스트 전용 사용자 200명 생성
--    username: loaduser_001 ~ loaduser_200
--    password: 'test1234' (BCrypt 해시)
--    tier_id = 1 (BRONZE 등급)
DO $$
DECLARE
    i INT;
    bcrypt_hash VARCHAR(255) := '$2a$12$x6neJj02p308E7C4.4kBt.EFpe27GKr6AIET0VY8oVZ/fESX6Evz.';
    -- 위 해시는 'test1234'의 BCrypt 인코딩 (cost=10)
    -- 실제 환경에서 Spring의 BCryptPasswordEncoder로 생성한 값으로 교체
BEGIN
    FOR i IN 1..200 LOOP
        INSERT INTO users (username, email, password_hash, name, phone, role,
                           tier_id, total_spent, point_balance, is_active, created_at, updated_at)
        VALUES (
            'loaduser_' || LPAD(i::text, 3, '0'),
            'loaduser_' || LPAD(i::text, 3, '0') || '@test.com',
            bcrypt_hash,
            '부하테스터' || i,
            '010-0000-' || LPAD(i::text, 4, '0'),
            'ROLE_USER',
            1,
            0,
            10000,
            true,
            NOW(),
            NOW()
        )
        ON CONFLICT (username) DO NOTHING;
    END LOOP;
    RAISE NOTICE '부하 테스트 사용자 200명 생성 완료';
END $$;

-- 2) 부하 테스트용 쿠폰 (선착순 50명 한정)
INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                     min_order_amount, max_discount, total_quantity, used_quantity,
                     valid_from, valid_until, is_active, created_at)
VALUES (
    'LOADTEST_RUSH',
    '부하테스트 선착순 쿠폰',
    'PERCENT',
    15,
    10000,
    5000,
    50,
    0,
    NOW(),
    NOW() + INTERVAL '1 day',
    true,
    NOW()
)
ON CONFLICT (coupon_code) DO UPDATE SET
    used_quantity = 0,
    valid_from = NOW(),
    valid_until = NOW() + INTERVAL '1 day',
    is_active = true;

-- 3) 확인
SELECT COUNT(*) AS load_test_users FROM users WHERE username LIKE 'loaduser_%';
SELECT coupon_code, total_quantity, used_quantity FROM coupons WHERE coupon_code = 'LOADTEST_RUSH';

-- 4) 테스트에 사용할 상품 ID 샘플 확인
SELECT product_id, product_name, stock_quantity, price
FROM products
WHERE is_active = true AND stock_quantity > 100
ORDER BY product_id
LIMIT 10;

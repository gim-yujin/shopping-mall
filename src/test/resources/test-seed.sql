-- 테스트 실행 시 최소 기준 데이터 고정

INSERT INTO user_tiers (
    tier_id,
    tier_name,
    tier_level,
    min_spent,
    discount_rate,
    point_earn_rate,
    free_shipping_threshold,
    description,
    created_at
)
VALUES
    (1, 'BASIC', 1, 0, 0, 1.00, NULL, '기본 등급', NOW()),
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

INSERT INTO categories (
    category_id,
    category_name,
    parent_category_id,
    level,
    display_order,
    is_active,
    created_at
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

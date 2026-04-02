\echo '== EXPLAIN benchmark dataset setup =='

SET synchronous_commit = off;
SET work_mem = '128MB';
SET maintenance_work_mem = '512MB';

TRUNCATE TABLE user_tier_history, search_logs, product_inventory_history,
             reviews, user_coupons, coupons, wishlists, carts,
             order_items, orders, product_images, products,
             categories, users, user_tiers
RESTART IDENTITY CASCADE;

INSERT INTO user_tiers (
    tier_name, tier_level, min_spent, discount_rate, point_earn_rate,
    free_shipping_threshold, description, created_at
)
VALUES
    ('BRONZE', 1, 0, 0, 1.0, 30000, 'Explain benchmark tier', NOW()),
    ('SILVER', 2, 300000, 2, 1.2, 20000, 'Explain benchmark tier', NOW()),
    ('GOLD', 3, 700000, 5, 1.5, 10000, 'Explain benchmark tier', NOW()),
    ('PLATINUM', 4, 1500000, 8, 2.0, 0, 'Explain benchmark tier', NOW());

INSERT INTO categories (
    category_name, parent_category_id, level, display_order, is_active, created_at
)
VALUES ('Explain Bench', NULL, 1, 0, true, NOW());

INSERT INTO products (
    product_name, category_id, description, price, original_price, stock_quantity,
    sales_count, view_count, rating_avg, review_count, is_active, created_at, updated_at, version
)
SELECT
    'Explain Product ' || LPAD(gs::text, 3, '0'),
    1,
    'Execution plan benchmark product',
    (10000 + (gs * 500))::numeric(12, 2),
    (12000 + (gs * 500))::numeric(12, 2),
    100000,
    0,
    0,
    0,
    0,
    true,
    NOW(),
    NOW(),
    0
FROM generate_series(1, 200) gs;

INSERT INTO users (
    username, email, password_hash, name, phone, role, tier_id,
    total_spent, point_balance, is_active, created_at, updated_at
)
SELECT
    'bench_user_' || LPAD(gs::text, 5, '0'),
    'bench_user_' || LPAD(gs::text, 5, '0') || '@example.com',
    '$2a$10$benchmarkplaceholderhashvaluefortests.only123456789012345678901',
    '벤치유저' || gs,
    '010-7000-' || LPAD((gs % 10000)::text, 4, '0'),
    'ROLE_USER',
    1,
    0,
    0,
    true,
    NOW(),
    NOW()
FROM generate_series(1, 30000) gs;

WITH order_base AS (
    SELECT
        gs AS seq,
        ((gs - 1) % 30000) + 1 AS user_id,
        CASE
            WHEN gs % 10 = 0 THEN 'CANCELLED'
            WHEN gs % 4 = 0 THEN 'DELIVERED'
            WHEN gs % 3 = 0 THEN 'SHIPPED'
            ELSE 'PAID'
        END AS order_status,
        (50000 + (gs % 50000))::numeric(15, 2) AS total_amount,
        CASE WHEN gs % 6 = 0 THEN 2000 ELSE 0 END::numeric(15, 2) AS tier_discount_amount,
        CASE WHEN gs % 5 = 0 THEN 3000 ELSE 0 END::numeric(15, 2) AS coupon_discount_amount,
        CASE WHEN gs % 4 = 0 THEN 0 ELSE 3000 END::numeric(8, 2) AS shipping_fee,
        CASE WHEN gs % 11 = 0 THEN 1000 ELSE 0 END AS used_points,
        TIMESTAMP '2021-01-01 00:00:00'
            + ((gs - 1) % 1825) * INTERVAL '1 day'
            + ((gs - 1) % 86400) * INTERVAL '1 second' AS order_date
    FROM generate_series(1, 600000) gs
)
INSERT INTO orders (
    order_number, user_id, order_status, total_amount, discount_amount,
    tier_discount_amount, coupon_discount_amount, shipping_fee, final_amount,
    point_earn_rate_snapshot, earned_points_snapshot, used_points,
    refunded_amount, refunded_points, points_settled, payment_method,
    shipping_address, recipient_name, recipient_phone, order_date,
    paid_at, shipped_at, tracking_number, carrier, delivered_at, cancelled_at
)
SELECT
    'BENCH-' || LPAD(seq::text, 7, '0'),
    user_id,
    order_status,
    total_amount,
    tier_discount_amount + coupon_discount_amount,
    tier_discount_amount,
    coupon_discount_amount,
    shipping_fee,
    total_amount - (tier_discount_amount + coupon_discount_amount) + shipping_fee,
    1.00,
    CASE WHEN order_status = 'DELIVERED' THEN 100 ELSE 0 END,
    used_points,
    CASE
        WHEN order_status = 'CANCELLED'
            THEN total_amount - (tier_discount_amount + coupon_discount_amount) + shipping_fee
        ELSE 0
    END,
    CASE WHEN order_status = 'CANCELLED' THEN used_points ELSE 0 END,
    order_status = 'DELIVERED',
    CASE seq % 4
        WHEN 0 THEN 'CARD'
        WHEN 1 THEN 'BANK'
        WHEN 2 THEN 'KAKAO'
        ELSE 'NAVER'
    END,
    '서울시 성능구 벤치로 ' || seq,
    '수령인' || seq,
    '010-8000-' || LPAD((seq % 10000)::text, 4, '0'),
    order_date,
    order_date + INTERVAL '5 minutes',
    CASE WHEN order_status IN ('SHIPPED', 'DELIVERED') THEN order_date + INTERVAL '1 day' END,
    CASE WHEN order_status IN ('SHIPPED', 'DELIVERED') THEN 'TRACK-' || seq END,
    CASE WHEN order_status IN ('SHIPPED', 'DELIVERED') THEN 'CJ' END,
    CASE WHEN order_status = 'DELIVERED' THEN order_date + INTERVAL '3 days' END,
    CASE WHEN order_status = 'CANCELLED' THEN order_date + INTERVAL '2 hours' END
FROM order_base;

INSERT INTO order_items (
    order_id, product_id, product_name, quantity, unit_price, discount_rate, subtotal,
    cancelled_quantity, returned_quantity, cancelled_amount, returned_amount,
    status, return_reason, reject_reason, pending_return_quantity,
    return_requested_at, returned_at, created_at
)
SELECT
    o.order_id,
    ((o.order_id + item_no - 1) % 200) + 1,
    'Explain Product ' || LPAD((((o.order_id + item_no - 1) % 200) + 1)::text, 3, '0'),
    item_no,
    (10000 + ((o.order_id + item_no) % 20000))::numeric(12, 2),
    0,
    (item_no * (10000 + ((o.order_id + item_no) % 20000)))::numeric(15, 2),
    CASE
        WHEN o.order_status = 'CANCELLED' AND item_no = 1 THEN item_no
        ELSE 0
    END,
    CASE
        WHEN (o.order_id + item_no) % 89 = 0 THEN item_no
        ELSE 0
    END,
    CASE
        WHEN o.order_status = 'CANCELLED' AND item_no = 1
            THEN (item_no * (10000 + ((o.order_id + item_no) % 20000)))::numeric(15, 2)
        ELSE 0
    END,
    CASE
        WHEN (o.order_id + item_no) % 89 = 0
            THEN (item_no * (10000 + ((o.order_id + item_no) % 20000)))::numeric(15, 2)
        ELSE 0
    END,
    CASE
        WHEN o.order_status = 'CANCELLED' AND item_no = 1 THEN 'CANCELLED'
        WHEN (o.order_id + item_no) % 67 = 0 THEN 'RETURN_REQUESTED'
        WHEN (o.order_id + item_no) % 89 = 0 THEN 'RETURNED'
        ELSE 'NORMAL'
    END,
    CASE
        WHEN (o.order_id + item_no) % 67 = 0 THEN 'DEFECT'
        ELSE NULL
    END,
    NULL,
    CASE
        WHEN (o.order_id + item_no) % 67 = 0 THEN 1
        ELSE 0
    END,
    CASE
        WHEN (o.order_id + item_no) % 67 = 0 THEN o.order_date + INTERVAL '2 days'
        ELSE NULL
    END,
    CASE
        WHEN (o.order_id + item_no) % 89 = 0 THEN o.order_date + INTERVAL '5 days'
        ELSE NULL
    END,
    o.order_date + make_interval(mins => item_no)
FROM orders o
CROSS JOIN generate_series(1, 3) item_no;

ANALYZE users;
ANALYZE products;
ANALYZE orders;
ANALYZE order_items;

SELECT COUNT(*) AS users_count FROM users;
SELECT COUNT(*) AS orders_count FROM orders;
SELECT COUNT(*) AS order_items_count FROM order_items;
SELECT COUNT(*) AS return_requested_count
FROM order_items
WHERE status = 'RETURN_REQUESTED';

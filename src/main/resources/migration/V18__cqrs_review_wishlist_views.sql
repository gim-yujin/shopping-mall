-- [Phase 22] CQRS 읽기 뷰: 리뷰 목록 + 위시리스트 목록
--
-- 리뷰: users JOIN으로 username을 한 번에 가져와 N+1 없이 리뷰 작성자명 표시.
-- 위시리스트: products JOIN + 썸네일 서브쿼리로 Hibernate.initialize() 우회.

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

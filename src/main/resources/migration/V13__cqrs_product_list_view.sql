-- [Phase 18] CQRS 읽기 모델 분리 — 상품 목록 읽기 전용 뷰
--
-- 문제: 상품 목록 페이지에서 Product 엔티티를 JOIN FETCH로 가져온 뒤
-- 썸네일 이미지를 Lazy 컬렉션에서 추출하여 N+1 쿼리가 발생했다.
-- 또한 description, version 등 목록에 불필요한 컬럼까지 SELECT되어
-- 네트워크/메모리를 낭비했다.
--
-- 해결: 필요한 컬럼만 선택하고 썸네일을 서브쿼리로 한 번에 가져오는 뷰를 생성한다.

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

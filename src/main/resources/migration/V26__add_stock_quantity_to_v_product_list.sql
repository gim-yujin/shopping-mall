-- V26: v_product_list 뷰에 stock_quantity 컬럼 추가
--
-- 배경:
--   상품 목록(베스트셀러/신상품/할인/검색/카테고리)이 v_product_list 뷰를 통해 플랫
--   프로젝션을 캐시한다. 기존 뷰는 stock_quantity를 노출하지 않아 다음 두 문제가 있었다.
--
--   1) 품절 상품(stock_quantity=0)이 리스트에 그대로 노출되었다. 클릭하면 상세 페이지에서
--      재고 0 → "품절" 메시지를 마주치게 되어 UX가 나빠진다.
--   2) 템플릿이 품절 배지를 렌더할 근거(stockQuantity 또는 soldOut 플래그)가 없었다.
--
-- 해결:
--   v_product_list에 stock_quantity 컬럼을 추가한다. 리스트 쿼리는 SELECT 컬럼만 늘리고,
--   ProductListReadModel.fromNativeRow가 이를 읽어 soldOut() 헬퍼를 제공한다.
--
--   리스트 자체에서 품절을 제외할지(WHERE stock_quantity > 0)는 별도 정책 결정 사항이라
--   본 마이그레이션은 컬럼 노출만 담당한다. 템플릿이 배지를 렌더하는 것으로 1차 UX 개선.

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
    p.is_active,
    p.stock_quantity
FROM products p
JOIN categories c ON c.category_id = p.category_id;

package com.shop.domain.product.repository;

import com.shop.domain.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // [Phase 8] 카테고리별 상품 목록 N+1 해결 — JOIN FETCH p.category.
    //
    // 문제: Product.category는 FetchType.LAZY로 설정되어 있다.
    // 목록 페이지에서 상품을 렌더링할 때 각 상품의 카테고리명(p.category.categoryName)에
    // 접근하면, 상품 N개에 대해 N번의 추가 SELECT가 발생한다 (N+1 문제).
    // 예: 20개 상품 목록 → 1(상품 목록) + 20(카테고리) = 21 쿼리.
    //
    // 해결: JOIN FETCH로 상품과 카테고리를 한 번의 쿼리로 즉시 로딩한다.
    // countQuery를 분리하여 Hibernate가 count 쿼리에 FETCH를 적용하는 오류를 방지한다.
    @Query(value = "SELECT p FROM Product p JOIN FETCH p.category WHERE p.isActive = true AND p.category.categoryId = :categoryId",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.isActive = true AND p.category.categoryId = :categoryId")
    Page<Product> findByCategoryId(@Param("categoryId") Integer categoryId, Pageable pageable);

    // [Phase 8] 다중 카테고리 상품 목록 N+1 해결 — JOIN FETCH p.category.
    // findByCategoryId와 동일한 N+1 문제가 IN 절 쿼리에서도 발생한다.
    @Query(value = "SELECT p FROM Product p JOIN FETCH p.category WHERE p.isActive = true AND p.category.categoryId IN :categoryIds",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.isActive = true AND p.category.categoryId IN :categoryIds")
    Page<Product> findByCategoryIds(@Param("categoryIds") List<Integer> categoryIds, Pageable pageable);

    @Query(value = "SELECT p.* FROM products p WHERE p.is_active = true AND to_tsvector('simple', p.product_name) @@ plainto_tsquery('simple', :keyword) ORDER BY p.sales_count DESC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.is_active = true AND to_tsvector('simple', p.product_name) @@ plainto_tsquery('simple', :keyword)",
           nativeQuery = true)
    Page<Product> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Product> searchByKeywordLike(@Param("keyword") String keyword, Pageable pageable);

    // [Phase 8] 베스트셀러 목록 N+1 해결 — JOIN FETCH p.category.
    //
    // 문제: 메인 페이지의 베스트셀러 섹션에서 상품 카드를 렌더링할 때
    // 카테고리명 표시를 위해 Lazy 프록시가 N번 초기화된다.
    // 메인 페이지는 트래픽이 가장 높은 페이지이므로 N+1의 영향이 가장 크다.
    //
    // 해결: JOIN FETCH로 단일 쿼리 처리. countQuery 분리로 페이징 호환성 보장.
    @Query(value = "SELECT p FROM Product p JOIN FETCH p.category WHERE p.isActive = true ORDER BY p.salesCount DESC",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.isActive = true")
    Page<Product> findBestSellers(Pageable pageable);

    // [Phase 8] 신상품 목록 N+1 해결 — JOIN FETCH p.category.
    // findBestSellers와 동일한 패턴. 신상품 페이지에서 카테고리 N+1을 제거한다.
    @Query(value = "SELECT p FROM Product p JOIN FETCH p.category WHERE p.isActive = true ORDER BY p.createdAt DESC",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.isActive = true")
    Page<Product> findNewArrivals(Pageable pageable);

    @Query(value = "SELECT p.* FROM products p WHERE p.is_active = true AND p.original_price IS NOT NULL AND p.original_price > p.price ORDER BY (p.original_price - p.price) DESC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.is_active = true AND p.original_price IS NOT NULL AND p.original_price > p.price",
           nativeQuery = true)
    Page<Product> findDeals(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.productId = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    // [Phase 8] 다건 상품 비관적 잠금 일괄 조회 (주문 생성 시 재고 차감 최적화).
    //
    // 문제: OrderCreationService.deductStockAndBuildOrderLines()에서
    // 장바구니 상품 N개에 대해 findByIdWithLock()을 N번 호출한다.
    // 각 호출마다 SELECT ... FOR UPDATE 쿼리가 발행되어,
    // 상품 5개 주문 시 5개의 개별 쿼리가 실행된다.
    //
    // 해결: IN 절로 한 번의 쿼리에서 N개 상품을 동시에 잠그고 조회한다.
    // SELECT ... WHERE product_id IN (...) FOR UPDATE는 PostgreSQL에서
    // 해당 행들을 모두 잠그므로, 개별 잠금과 동일한 동시성 보장을 제공한다.
    // 쿼리 수가 N → 1로 감소하여 네트워크 왕복(round-trip) 횟수가 줄어든다.
    //
    // 주의: 호출 전에 productIds를 오름차순 정렬하여 전달해야 한다.
    // 서로 다른 트랜잭션이 동일 상품 집합을 다른 순서로 잠그면 데드락이 발생할 수 있다.
    // OrderCreationService에서는 이미 cartItems를 productId 순으로 정렬하고 있다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.productId IN :ids ORDER BY p.productId")
    List<Product> findAllByIdInWithLock(@Param("ids") List<Long> ids);

    // V2(낙관적 잠금) 벤치마크용: 잠금 없이 상품 ID 순으로 조회.
    // findAllByIdInWithLock()과 동일한 쿼리이나 @Lock이 없어 FOR UPDATE가 발생하지 않는다.
    @Query("SELECT p FROM Product p WHERE p.productId IN :ids ORDER BY p.productId")
    List<Product> findAllByIdInOrderByProductId(@Param("ids") List<Long> ids);

    // V3(CAS UPDATE) 벤치마크용: 단일 UPDATE로 재고 차감 + 판매량 증가를 원자적으로 수행.
    // 반환값이 0이면 stock_quantity < quantity이므로 재고 부족을 의미한다.
    // @Version 필드를 수동으로 증가시켜 관리자 낙관적 잠금과의 정합성을 유지한다.
    @Modifying
    @Query("UPDATE Product p SET p.stockQuantity = p.stockQuantity - :quantity, "
         + "p.salesCount = p.salesCount + :quantity, "
         + "p.version = p.version + 1 "
         + "WHERE p.productId = :id AND p.stockQuantity >= :quantity")
    int decreaseStockAtomic(@Param("id") Long id, @Param("quantity") int quantity);

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category WHERE p.productId = :id")
    Optional<Product> findByIdWithCategory(@Param("id") Long id);

    Page<Product> findByIsActiveTrue(Pageable pageable);

    @Modifying
    @Query("UPDATE Product p SET p.viewCount = p.viewCount + 1 WHERE p.productId = :id")
    void incrementViewCount(@Param("id") Long id);

    // ── [Phase 18] CQRS 읽기 전용 네이티브 쿼리 ─────────────────────────────
    //
    // 문제: 기존 JPQL(JOIN FETCH) 쿼리는 Product 엔티티 전체를 영속성 컨텍스트에 로딩한다.
    // 목록 페이지에는 10개 컬럼만 필요한데, description/version/updatedAt 등
    // 불필요한 컬럼까지 SELECT되고, JPA 스냅샷 보관으로 GC 부담이 증가한다.
    //
    // 해결: v_product_list 뷰를 활용한 네이티브 쿼리로 필요한 컬럼만 SELECT하고,
    // 썸네일 URL을 서브쿼리로 한 번에 가져와 N+1 문제를 원천 차단한다.
    // 결과는 Object[]로 반환되어 ProductListReadModel에 직접 매핑된다.

    /**
     * [Phase 18] 베스트셀러 목록 — 플랫 프로젝션.
     * 기존 findBestSellers()의 JPQL JOIN FETCH를 대체한다.
     */
    @Query(value = "SELECT product_id, product_name, price, original_price, rating_avg, "
            + "review_count, sales_count, category_id, category_name, created_at, thumbnail_url, is_active "
            + "FROM v_product_list WHERE is_active = true ORDER BY sales_count DESC",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true",
            nativeQuery = true)
    Page<Object[]> findBestSellersFlat(Pageable pageable);

    /**
     * [Phase 18] 신상품 목록 — 플랫 프로젝션.
     */
    @Query(value = "SELECT product_id, product_name, price, original_price, rating_avg, "
            + "review_count, sales_count, category_id, category_name, created_at, thumbnail_url, is_active "
            + "FROM v_product_list WHERE is_active = true ORDER BY created_at DESC",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true",
            nativeQuery = true)
    Page<Object[]> findNewArrivalsFlat(Pageable pageable);

    /**
     * [Phase 18] 할인 상품 목록 — 플랫 프로젝션.
     */
    @Query(value = "SELECT product_id, product_name, price, original_price, rating_avg, "
            + "review_count, sales_count, category_id, category_name, created_at, thumbnail_url, is_active "
            + "FROM v_product_list WHERE is_active = true AND original_price IS NOT NULL AND original_price > price "
            + "ORDER BY (original_price - price) DESC",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true AND original_price IS NOT NULL AND original_price > price",
            nativeQuery = true)
    Page<Object[]> findDealsFlat(Pageable pageable);

    /**
     * [Phase 18] 전체 상품 목록 (활성만) — 플랫 프로젝션.
     */
    @Query(value = "SELECT product_id, product_name, price, original_price, rating_avg, "
            + "review_count, sales_count, category_id, category_name, created_at, thumbnail_url, is_active "
            + "FROM v_product_list WHERE is_active = true",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true",
            nativeQuery = true)
    Page<Object[]> findActiveProductsFlat(Pageable pageable);

    /**
     * [Phase 18] 다중 카테고리 상품 목록 — 플랫 프로젝션.
     */
    @Query(value = "SELECT product_id, product_name, price, original_price, rating_avg, "
            + "review_count, sales_count, category_id, category_name, created_at, thumbnail_url, is_active "
            + "FROM v_product_list WHERE is_active = true AND category_id IN :categoryIds",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true AND category_id IN :categoryIds",
            nativeQuery = true)
    Page<Object[]> findByCategoryIdsFlat(@Param("categoryIds") List<Integer> categoryIds, Pageable pageable);

    /**
     * [Phase 18] 키워드 검색 (FTS) — 플랫 프로젝션.
     * 기존 searchByKeyword()를 대체하여 썸네일을 한 번에 가져온다.
     */
    @Query(value = "SELECT v.product_id, v.product_name, v.price, v.original_price, v.rating_avg, "
            + "v.review_count, v.sales_count, v.category_id, v.category_name, v.created_at, v.thumbnail_url, v.is_active "
            + "FROM v_product_list v "
            + "WHERE v.is_active = true AND to_tsvector('simple', v.product_name) @@ plainto_tsquery('simple', :keyword) "
            + "ORDER BY v.sales_count DESC",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true AND to_tsvector('simple', product_name) @@ plainto_tsquery('simple', :keyword)",
            nativeQuery = true)
    Page<Object[]> searchByKeywordFlat(@Param("keyword") String keyword, Pageable pageable);

    /**
     * [Phase 18] 키워드 LIKE 검색 (FTS 폴백) — 플랫 프로젝션.
     */
    @Query(value = "SELECT v.product_id, v.product_name, v.price, v.original_price, v.rating_avg, "
            + "v.review_count, v.sales_count, v.category_id, v.category_name, v.created_at, v.thumbnail_url, v.is_active "
            + "FROM v_product_list v "
            + "WHERE v.is_active = true AND LOWER(v.product_name) LIKE LOWER(CONCAT('%', :keyword, '%'))",
            countQuery = "SELECT COUNT(*) FROM products WHERE is_active = true AND LOWER(product_name) LIKE LOWER(CONCAT('%', :keyword, '%'))",
            nativeQuery = true)
    Page<Object[]> searchByKeywordLikeFlat(@Param("keyword") String keyword, Pageable pageable);
}

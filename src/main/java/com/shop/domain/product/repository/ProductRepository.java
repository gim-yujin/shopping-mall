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

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category WHERE p.productId = :id")
    Optional<Product> findByIdWithCategory(@Param("id") Long id);

    Page<Product> findByIsActiveTrue(Pageable pageable);

    @Modifying
    @Query("UPDATE Product p SET p.viewCount = p.viewCount + 1 WHERE p.productId = :id")
    void incrementViewCount(@Param("id") Long id);
}

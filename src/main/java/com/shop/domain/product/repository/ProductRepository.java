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

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.category.categoryId = :categoryId ORDER BY p.salesCount DESC")
    Page<Product> findByCategoryId(@Param("categoryId") Integer categoryId, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.category.categoryId IN :categoryIds ORDER BY p.salesCount DESC")
    Page<Product> findByCategoryIds(@Param("categoryIds") List<Integer> categoryIds, Pageable pageable);

    @Query(value = "SELECT p.* FROM products p WHERE p.is_active = true AND to_tsvector('simple', p.product_name) @@ plainto_tsquery('simple', :keyword) ORDER BY p.sales_count DESC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.is_active = true AND to_tsvector('simple', p.product_name) @@ plainto_tsquery('simple', :keyword)",
           nativeQuery = true)
    Page<Product> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Product> searchByKeywordLike(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true ORDER BY p.salesCount DESC")
    Page<Product> findBestSellers(Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true ORDER BY p.createdAt DESC")
    Page<Product> findNewArrivals(Pageable pageable);

    @Query(value = "SELECT p.* FROM products p WHERE p.is_active = true AND p.original_price IS NOT NULL AND p.original_price > p.price ORDER BY (p.original_price - p.price) DESC",
           countQuery = "SELECT COUNT(*) FROM products p WHERE p.is_active = true AND p.original_price IS NOT NULL AND p.original_price > p.price",
           nativeQuery = true)
    Page<Product> findDeals(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.productId = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Product p SET p.viewCount = p.viewCount + 1 WHERE p.productId = :id")
    void incrementViewCount(@Param("id") Long id);
}

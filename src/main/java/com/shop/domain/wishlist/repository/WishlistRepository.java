package com.shop.domain.wishlist.repository;

import com.shop.domain.wishlist.entity.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    @Query("SELECT w FROM Wishlist w JOIN FETCH w.product WHERE w.userId = :userId ORDER BY w.createdAt DESC")
    Page<Wishlist> findByUserIdWithProduct(@Param("userId") Long userId, Pageable pageable);

    Optional<Wishlist> findByUserIdAndProduct_ProductId(Long userId, Long productId);

    boolean existsByUserIdAndProduct_ProductId(Long userId, Long productId);

    void deleteByUserIdAndProduct_ProductId(Long userId, Long productId);

    // 네이티브 DELETE — 영향받은 행 수 반환 (0 또는 1)
    @Modifying
    @Query(value = "DELETE FROM wishlists WHERE user_id = :userId AND product_id = :productId",
           nativeQuery = true)
    int deleteByUserIdAndProductIdNative(@Param("userId") Long userId, @Param("productId") Long productId);

    // 네이티브 INSERT ON CONFLICT DO NOTHING — 영향받은 행 수 반환 (0 또는 1)
    @Modifying
    @Query(value = "INSERT INTO wishlists (user_id, product_id, created_at) " +
                   "VALUES (:userId, :productId, NOW()) " +
                   "ON CONFLICT (user_id, product_id) DO NOTHING",
           nativeQuery = true)
    int insertIgnoreConflict(@Param("userId") Long userId, @Param("productId") Long productId);
}

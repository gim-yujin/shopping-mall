package com.shop.domain.cart.repository;

import com.shop.domain.cart.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    @Query("SELECT c FROM Cart c JOIN FETCH c.product WHERE c.userId = :userId ORDER BY c.updatedAt DESC")
    List<Cart> findByUserIdWithProduct(@Param("userId") Long userId);

    Optional<Cart> findByUserIdAndProduct_ProductId(Long userId, Long productId);

    void deleteByUserIdAndProduct_ProductId(Long userId, Long productId);

    void deleteByUserId(Long userId);

    @Query("SELECT COUNT(c) FROM Cart c WHERE c.userId = :userId")
    int countByUserId(@Param("userId") Long userId);

    /**
     * 사용자별 장바구니 작업 직렬화를 위한 Advisory Lock.
     * 트랜잭션이 커밋/롤백되면 자동 해제됨.
     * 같은 userId에 대한 동시 addToCart 호출을 순차 실행으로 만듦.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(:userId)", nativeQuery = true)
    void acquireUserCartLock(@Param("userId") Long userId);
}

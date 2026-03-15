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

    /**
     * [P1-6] 장바구니 선택 주문: 특정 장바구니 항목 ID 목록으로 조회.
     *
     * userId 조건을 함께 걸어 다른 사용자의 장바구니를 조회하는 것을 방지한다.
     * JOIN FETCH로 Product를 즉시 로드하여 N+1 문제를 방지한다.
     */
    @Query("SELECT c FROM Cart c JOIN FETCH c.product WHERE c.userId = :userId AND c.cartId IN :cartIds ORDER BY c.updatedAt DESC")
    List<Cart> findByUserIdAndCartIdIn(@Param("userId") Long userId, @Param("cartIds") List<Long> cartIds);

    Optional<Cart> findByUserIdAndProduct_ProductId(Long userId, Long productId);

    void deleteByUserIdAndProduct_ProductId(Long userId, Long productId);

    void deleteByUserId(Long userId);

    @Query("SELECT COUNT(c) FROM Cart c WHERE c.userId = :userId")
    int countByUserId(@Param("userId") Long userId);

    /**
     * 사용자별 장바구니/주문 작업 직렬화를 위한 Advisory Lock.
     * 트랜잭션이 커밋/롤백되면 자동 해제됨.
     * 같은 userId에 대한 동시 addToCart/createOrder 호출을 순차 실행으로 만듦.
     *
     * [P1 FIX] Advisory Lock 네임스페이스 분리.
     *
     * 기존 문제: pg_advisory_xact_lock(userId) 단일 인자 형태를 사용하여
     * 모든 Advisory Lock이 하나의 네임스페이스를 공유했다.
     * 향후 다른 도메인(예: 쿠폰 선착순 발급)에서도 Advisory Lock을 도입하면
     * userId가 동일한 경우 의도치 않은 교차 잠금이 발생할 수 있었다.
     *
     * 수정: pg_advisory_xact_lock(namespace, key) 2인자 형태로 변경하여
     * CART_ORDER_LOCK_NS(1001) 네임스페이스 내에서만 잠금이 유효하도록 격리한다.
     * 다른 도메인은 별도의 namespace 상수를 사용하여 충돌을 방지한다.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(1001, CAST(:userId AS INT))", nativeQuery = true)
    Object acquireUserCartLock(@Param("userId") Long userId);
}

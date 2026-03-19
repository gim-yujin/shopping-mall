package com.shop.domain.order.repository;

import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("SELECT o FROM Order o WHERE o.userId = :userId ORDER BY o.orderDate DESC")
    Page<Order> findByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * [Phase 2 성능] 페이징 조회 후 주문 아이템을 일괄 로드하는 2-쿼리 패턴용 메서드.
     *
     * <p><b>문제:</b> 페이징 쿼리에 JOIN FETCH를 직접 사용하면 Hibernate가 전체 결과를
     * 메모리에 로드한 뒤 애플리케이션 레벨에서 페이징한다(HHH000104 경고).
     * 데이터가 많을수록 OOM 위험이 커진다.</p>
     *
     * <p><b>기존 해결:</b> {@code Hibernate.initialize()} + {@code batch_fetch_size=100}으로
     * Lazy 컬렉션을 초기화했으나, Hibernate 내부 배치 전략에 의존하여 동작이 암묵적이었다.</p>
     *
     * <p><b>개선:</b> 2-쿼리 패턴으로 명시적 데이터 로딩.
     * <ol>
     *   <li>1차 쿼리: 페이징 조회 (Order만, items 미로드)</li>
     *   <li>2차 쿼리: 1차 결과의 orderId 목록으로 JOIN FETCH → items 일괄 로드</li>
     * </ol>
     * 2차 쿼리는 고정된 ID 목록에 대한 IN 절이므로 HHH000104가 발생하지 않는다.
     * 반환된 Order 엔티티는 영속성 컨텍스트에 이미 존재하는 엔티티와 병합되어,
     * 이후 {@code order.getItems()} 호출 시 추가 쿼리 없이 아이템에 접근할 수 있다.</p>
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.orderId IN :orderIds")
    List<Order> findWithItemsByOrderIds(@Param("orderIds") List<Long> orderIds);

    Optional<Order> findByOrderNumber(String orderNumber);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.userId = :userId AND o.orderId = :orderId")
    Optional<Order> findByIdAndUserId(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId AND o.userId = :userId")
    Optional<Order> findByIdAndUserIdWithLock(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdWithLock(@Param("orderId") Long orderId);

    Page<Order> findAllByOrderByOrderDateDesc(Pageable pageable);

    @Query("SELECT o FROM Order o WHERE o.orderStatus = :status ORDER BY o.orderDate DESC")
    Page<Order> findByStatus(@Param("status") OrderStatus status, Pageable pageable);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.orderStatus = :status")
    long countByStatus(@Param("status") OrderStatus status);

    @Query("SELECT o.userId, COALESCE(SUM(o.finalAmount), 0) FROM Order o " +
           "WHERE o.orderStatus <> com.shop.domain.order.entity.OrderStatus.CANCELLED " +
           "AND o.orderDate >= :startDate AND o.orderDate < :endDate " +
           "GROUP BY o.userId")
    List<Object[]> findYearlySpentByUser(@Param("startDate") java.time.LocalDateTime startDate,
                                          @Param("endDate") java.time.LocalDateTime endDate);

    // ── [Phase 18] CQRS 읽기 전용 네이티브 쿼리 ─────────────────────────────
    //
    // 문제: 기존 주문 목록 조회는 Page<Order>를 반환한 후 fetchOrderItems()로
    // 2차 쿼리를 발행하여 아이템 수를 계산했다. 목록에 필요한 건 주문 요약 정보와
    // 아이템 수뿐인데, 전체 OrderItem 컬렉션을 메모리에 올렸다.
    //
    // 해결: v_order_list 뷰를 활용한 네이티브 쿼리로 필요한 컬럼만 SELECT하고,
    // item_count와 first_product_name을 서브쿼리로 한 번에 가져와
    // 2-쿼리 패턴을 제거한다.

    /**
     * [Phase 18] 사용자별 주문 목록 — 플랫 프로젝션.
     * 기존 findByUserId() + fetchOrderItems() 2-쿼리 패턴을 단일 쿼리로 대체한다.
     */
    @Query(value = "SELECT order_id, order_number, user_id, order_status, total_amount, "
            + "discount_amount, shipping_fee, final_amount, order_date, paid_at, "
            + "shipped_at, delivered_at, cancelled_at, item_count, first_product_name "
            + "FROM v_order_list WHERE user_id = :userId ORDER BY order_date DESC",
            countQuery = "SELECT COUNT(*) FROM orders WHERE user_id = :userId",
            nativeQuery = true)
    Page<Object[]> findByUserIdFlat(@Param("userId") Long userId, Pageable pageable);

    /**
     * [Phase 18] 전체 주문 목록 (관리자용) — 플랫 프로젝션.
     * 기존 findAllByOrderByOrderDateDesc() + fetchOrderItems() 2-쿼리 패턴을 대체한다.
     */
    @Query(value = "SELECT order_id, order_number, user_id, order_status, total_amount, "
            + "discount_amount, shipping_fee, final_amount, order_date, paid_at, "
            + "shipped_at, delivered_at, cancelled_at, item_count, first_product_name "
            + "FROM v_order_list ORDER BY order_date DESC",
            countQuery = "SELECT COUNT(*) FROM orders",
            nativeQuery = true)
    Page<Object[]> findAllOrdersFlat(Pageable pageable);

    /**
     * [Phase 18] 상태별 주문 목록 (관리자용) — 플랫 프로젝션.
     * 기존 findByStatus() + fetchOrderItems() 2-쿼리 패턴을 대체한다.
     */
    @Query(value = "SELECT order_id, order_number, user_id, order_status, total_amount, "
            + "discount_amount, shipping_fee, final_amount, order_date, paid_at, "
            + "shipped_at, delivered_at, cancelled_at, item_count, first_product_name "
            + "FROM v_order_list WHERE order_status = :status ORDER BY order_date DESC",
            countQuery = "SELECT COUNT(*) FROM orders WHERE order_status = :status",
            nativeQuery = true)
    Page<Object[]> findByStatusFlat(@Param("status") String status, Pageable pageable);
}

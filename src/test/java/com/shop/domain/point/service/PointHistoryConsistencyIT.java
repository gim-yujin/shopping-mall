package com.shop.domain.point.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.service.OrderCancellationService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.point.entity.PointHistory;
import com.shop.domain.point.repository.PointHistoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [정합성] PointHistory ↔ Order 합산 정합성 통합 테스트.
 *
 * <p>운영 점검 SQL(`docs/order-invariant-checks.md` 점검 6/7)이 검출하려는 두 불변식을
 * 실제 부분취소/반품/전체취소 코드 경로에서 검증한다.</p>
 *
 * <ol>
 *   <li>SUM(USE.amount) = orders.used_points (주문 1건당 USE 1행)</li>
 *   <li>SUM(REFUND.amount WHERE ref_type IN ('CANCEL','PARTIAL_CANCEL','RETURN'))
 *       = orders.refunded_points</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class PointHistoryConsistencyIT {

    @Autowired private OrderService orderService;
    @Autowired private OrderCancellationService orderCancellationService;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long testProductId;

    private Map<String, Object> originalUserState;
    private Map<String, Object> originalProductState;
    private final List<Long> createdOrderIds = new ArrayList<>();

    private static final int INITIAL_POINTS = 50_000;

    @BeforeEach
    void setUp() {
        ensurePointHistoryReferenceTypeConstraint();

        testProductId = jdbcTemplate.queryForObject(
                """
                SELECT product_id FROM products
                WHERE is_active = true AND stock_quantity >= 100
                ORDER BY product_id LIMIT 1
                """,
                Long.class);
        originalProductState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);

        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);
        originalUserState = jdbcTemplate.queryForMap(
                "SELECT total_spent, point_balance, tier_id FROM users WHERE user_id = ?",
                testUserId);

        // usePoints 시나리오를 위해 포인트 잔액을 충분히 확보
        jdbcTemplate.update(
                "UPDATE users SET point_balance = ? WHERE user_id = ?",
                INITIAL_POINTS, testUserId);
    }

    @AfterEach
    void tearDown() {
        for (Long orderId : createdOrderIds) {
            jdbcTemplate.update(
                    "DELETE FROM point_history WHERE reference_id = ? "
                            + "AND reference_type IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN')",
                    orderId);
            jdbcTemplate.update(
                    "UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL "
                            + "WHERE order_id = ?", orderId);
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE reference_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", orderId);
            jdbcTemplate.update("DELETE FROM orders WHERE order_id = ?", orderId);
        }
        createdOrderIds.clear();
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);

        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalProductState.get("stock_quantity"),
                originalProductState.get("sales_count"),
                testProductId);
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserState.get("total_spent"),
                originalUserState.get("point_balance"),
                originalUserState.get("tier_id"),
                testUserId);
    }

    private void ensurePointHistoryReferenceTypeConstraint() {
        jdbcTemplate.execute("ALTER TABLE point_history DROP CONSTRAINT IF EXISTS chk_point_reference_type");
        jdbcTemplate.execute("""
                ALTER TABLE point_history
                ADD CONSTRAINT chk_point_reference_type CHECK (
                    reference_type IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN', 'ADMIN', 'SYSTEM')
                )
                """);
    }

    @Test
    @DisplayName("주문 생성 — USE 합 = orders.used_points")
    void orderCreate_useSumMatchesUsedPoints() {
        Order order = createOrderUsingPoints(3, 1_000);
        long orderId = order.getOrderId();

        long useSum = pointHistoryRepository.sumUsedPointsByOrderId(orderId);
        int usedPoints = jdbcTemplate.queryForObject(
                "SELECT used_points FROM orders WHERE order_id = ?", Integer.class, orderId);

        assertThat(usedPoints).isEqualTo(1_000);
        assertThat(useSum).as("USE 합산 = orders.used_points").isEqualTo(usedPoints);
    }

    @Test
    @DisplayName("부분 취소 1회 — REFUND 합 = orders.refunded_points (PARTIAL_CANCEL)")
    void partialCancelOnce_refundSumMatchesRefundedPoints() {
        Order order = createOrderUsingPoints(3, 999);
        long orderId = order.getOrderId();
        Long itemId = findOrderItemId(orderId);

        orderService.partialCancel(orderId, testUserId, itemId, 1);

        assertRefundConsistency(orderId);
        // PARTIAL_CANCEL reference_type 확인
        long partialCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_history WHERE reference_id = ? "
                        + "AND change_type = 'REFUND' AND reference_type = 'PARTIAL_CANCEL'",
                Long.class, orderId);
        assertThat(partialCount).as("PARTIAL_CANCEL 환불 행 1건").isEqualTo(1L);
    }

    @Test
    @DisplayName("부분 취소 2회 누적 — REFUND 합 = orders.refunded_points")
    void partialCancelTwice_refundSumMatchesRefundedPoints() {
        Order order = createOrderUsingPoints(4, 2_000);
        long orderId = order.getOrderId();
        Long itemId = findOrderItemId(orderId);

        orderService.partialCancel(orderId, testUserId, itemId, 1);
        orderService.partialCancel(orderId, testUserId, itemId, 1);

        assertRefundConsistency(orderId);
        long refundRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_history WHERE reference_id = ? "
                        + "AND change_type = 'REFUND' AND reference_type = 'PARTIAL_CANCEL'",
                Long.class, orderId);
        assertThat(refundRowCount).as("부분 취소 2회 → REFUND 행 2건").isEqualTo(2L);
    }

    @Test
    @DisplayName("부분 취소 후 전체 취소 — REFUND 합 = orders.refunded_points (PARTIAL_CANCEL+CANCEL)")
    void partialThenFullCancel_refundSumMatchesRefundedPoints() {
        Order order = createOrderUsingPoints(3, 1_500);
        long orderId = order.getOrderId();
        Long itemId = findOrderItemId(orderId);

        // 1) 부분취소 1건 → PARTIAL_CANCEL REFUND 행 생성
        orderService.partialCancel(orderId, testUserId, itemId, 1);
        // 2) 잔여 2개 전체 취소 → CANCEL REFUND 행 추가 (남은 사용 포인트만 환불)
        orderCancellationService.cancelOrder(orderId, testUserId);

        assertRefundConsistency(orderId);

        // 두 종류의 reference_type이 공존
        List<String> refundTypes = jdbcTemplate.queryForList(
                "SELECT reference_type FROM point_history WHERE reference_id = ? "
                        + "AND change_type = 'REFUND' ORDER BY created_at",
                String.class, orderId);
        assertThat(refundTypes).containsExactlyInAnyOrder("PARTIAL_CANCEL", "CANCEL");
    }

    @Test
    @DisplayName("findByOrderId — PARTIAL_CANCEL/RETURN/CANCEL 모든 경로의 이력을 함께 반환")
    void findByOrderId_returnsAllRefundReferenceTypes() {
        Order order = createOrderUsingPoints(3, 1_500);
        long orderId = order.getOrderId();
        Long itemId = findOrderItemId(orderId);

        orderService.partialCancel(orderId, testUserId, itemId, 1);
        orderCancellationService.cancelOrder(orderId, testUserId);

        List<PointHistory> histories = pointHistoryRepository.findByOrderId(orderId);

        assertThat(histories)
                .as("USE + PARTIAL_CANCEL REFUND + CANCEL REFUND가 모두 조회되어야 함")
                .extracting(PointHistory::getReferenceType)
                .containsExactlyInAnyOrder("ORDER", "PARTIAL_CANCEL", "CANCEL");
    }

    /**
     * 부분 취소/전체 취소 후 각 주문에 대해 SUM(REFUND) = orders.refunded_points 확인.
     */
    private void assertRefundConsistency(long orderId) {
        long refundSum = pointHistoryRepository.sumRefundedPointsByOrderId(orderId);
        int refundedPoints = jdbcTemplate.queryForObject(
                "SELECT refunded_points FROM orders WHERE order_id = ?", Integer.class, orderId);
        assertThat(refundSum)
                .as("REFUND 합산 = orders.refunded_points (운영 점검 7과 동치)")
                .isEqualTo(refundedPoints);
    }

    private Order createOrderUsingPoints(int quantity, int usePoints) {
        addCartItem(quantity);
        OrderCreateRequest req = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, usePoints, null);
        Order order = orderService.createOrder(testUserId, req);
        createdOrderIds.add(order.getOrderId());
        return order;
    }

    private void addCartItem(int quantity) {
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                testUserId, testProductId, quantity, now, now);
    }

    private Long findOrderItemId(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_item_id FROM order_items WHERE order_id = ? AND product_id = ?",
                Long.class, orderId, testProductId);
    }
}

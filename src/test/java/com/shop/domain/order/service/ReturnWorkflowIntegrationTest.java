package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * [Step 4] 반품 워크플로우 통합 테스트 — Controller + 사용자 UI 관점.
 *
 * <h3>검증 범위</h3>
 *
 * <p>Step 4의 핵심은 사용자 경험 관점에서 반품 워크플로우가 올바르게 동작하는지
 * 검증하는 것이다. OrderController가 서비스에 위임하는 thin 레이어이므로,
 * OrderService를 통해 end-to-end 플로우를 검증한다.</p>
 *
 * <h4>반품 신청 → 승인 전체 플로우</h4>
 * <ul>
 *   <li>returnReason이 OrderItem에 정확히 기록되는지</li>
 *   <li>신청 시 상태가 RETURN_REQUESTED로 전이되는지</li>
 *   <li>승인 후 상태가 RETURNED로 전이되고 재고/환불이 처리되는지</li>
 *   <li>PointHistory에 RETURN 환불 기록이 생성되는지</li>
 * </ul>
 *
 * <h4>반품 신청 → 거절 플로우</h4>
 * <ul>
 *   <li>거절 시 상태가 RETURN_REJECTED로 전이되는지</li>
 *   <li>거절 사유(rejectReason)가 기록되는지</li>
 *   <li>재고/환불 변경이 없는지</li>
 * </ul>
 *
 * <h4>거절 후 재신청 → 승인 플로우</h4>
 * <ul>
 *   <li>RETURN_REJECTED 상태에서 재신청이 가능한지</li>
 *   <li>재신청 후 승인 시 최종 환불이 정확한지</li>
 * </ul>
 *
 * <h4>상태 기반 폼 노출 조건 검증</h4>
 * <ul>
 *   <li>RETURN_REQUESTED 상태 아이템에 반품 신청 불가 (중복 방지)</li>
 *   <li>RETURNED 상태 아이템에 추가 작업 불가 (종결 상태)</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class ReturnWorkflowIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long testProductId;

    private Map<String, Object> originalUserState;
    private Map<String, Object> originalProductState;
    private final List<Long> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ensurePointHistoryReferenceTypeConstraint();

        // 재고 충분한 활성 상품 선택
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

        // 장바구니 비어있는 활성 사용자 선택
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
    }

    @AfterEach
    void tearDown() {
        for (Long orderId : createdOrderIds) {
            jdbcTemplate.update(
                    "DELETE FROM point_history WHERE reference_id = ? AND reference_type IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN')",
                    orderId);
            jdbcTemplate.update(
                    "UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL WHERE order_id = ?",
                    orderId);
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE reference_id = ?", orderId);
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

    // ── 헬퍼 ─────────────────────────────────────────────────

    private void addCartItem(int quantity) {
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                testUserId, testProductId, quantity, now, now);
    }

    private OrderCreateRequest defaultRequest() {
        return new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, null, null);
    }

    /**
     * DELIVERED 상태의 주문을 생성한다.
     * 주문 생성 → PAID → SHIPPED → DELIVERED 전이를 수행한다.
     */
    private Order createDeliveredOrder(int quantity) {
        addCartItem(quantity);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        return order;
    }

    private Long findOrderItemId(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_item_id FROM order_items WHERE order_id = ? AND product_id = ?",
                Long.class, orderId, testProductId);
    }

    private Map<String, Object> getOrderItemState(Long orderItemId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, return_reason, reject_reason,
                       pending_return_quantity, returned_quantity,
                       return_requested_at, returned_at
                FROM order_items WHERE order_item_id = ?
                """,
                orderItemId);
    }

    // ====================================================================
    // 반품 신청 → 승인 전체 플로우
    // ====================================================================

    /**
     * [Step 4 핵심 검증] 반품 신청 시 returnReason이 DB에 정확히 기록되고,
     * 상태가 RETURN_REQUESTED로 전이되는지 확인한다.
     *
     * <p>이 테스트는 사용자 UI에서 반품 사유 <select>로 선택한 값이
     * OrderController → OrderService → PartialCancellationService를 거쳐
     * OrderItem.returnReason에 정확히 저장되는지 검증한다.</p>
     */
    @Test
    @DisplayName("반품 신청 — returnReason이 DB에 기록되고 상태가 RETURN_REQUESTED로 전이")
    void requestReturn_savesReturnReasonAndTransitionsStatus() {
        // Given: DELIVERED 상태 주문
        Order order = createDeliveredOrder(3);
        Long itemId = findOrderItemId(order.getOrderId());

        // When: DEFECT 사유로 반품 1개 신청
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // Then: DB 상태 확인
        Map<String, Object> state = getOrderItemState(itemId);
        assertThat(state.get("status")).isEqualTo("RETURN_REQUESTED");
        assertThat(state.get("return_reason")).isEqualTo("DEFECT");
        assertThat((Integer) state.get("pending_return_quantity")).isEqualTo(1);
        assertThat(state.get("return_requested_at")).isNotNull();
        // 아직 승인되지 않았으므로 returned_quantity는 0
        assertThat((Integer) state.get("returned_quantity")).isZero();
    }

    /**
     * 다양한 반품 사유가 모두 올바르게 저장되는지 검증한다.
     * UI의 <select> 옵션에 대응하는 모든 사유 코드를 확인한다.
     */
    @Test
    @DisplayName("반품 사유 코드 — 모든 사유(DEFECT, WRONG_ITEM, CHANGE_OF_MIND, SIZE_ISSUE, OTHER)가 저장된다")
    void requestReturn_allReasonCodesArePersisted() {
        String[] reasons = {"DEFECT", "WRONG_ITEM", "CHANGE_OF_MIND", "SIZE_ISSUE", "OTHER"};

        for (String reason : reasons) {
            Order order = createDeliveredOrder(1);
            Long itemId = findOrderItemId(order.getOrderId());

            orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, reason);

            String savedReason = jdbcTemplate.queryForObject(
                    "SELECT return_reason FROM order_items WHERE order_item_id = ?",
                    String.class, itemId);
            assertThat(savedReason).as("반품 사유 '%s'가 올바르게 저장되어야 한다", reason)
                    .isEqualTo(reason);
        }
    }

    /**
     * 반품 신청 → 관리자 승인 전체 플로우.
     *
     * <p>사용자 반품 신청 후 관리자가 승인하면:
     * - 상태가 RETURNED로 전이
     * - 재고가 복구
     * - 환불 금액이 기록
     * - pendingReturnQuantity가 0으로 리셋</p>
     */
    @Test
    @DisplayName("반품 신청 → 승인 — 상태 RETURNED, 재고 복구, 환불 금액 기록")
    void requestThenApprove_fullFlow() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());
        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // When: 신청 → 승인
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "WRONG_ITEM");
        orderService.approveReturn(order.getOrderId(), itemId);

        // Then: 상태 = RETURNED
        Map<String, Object> state = getOrderItemState(itemId);
        assertThat(state.get("status")).isEqualTo("RETURNED");
        assertThat((Integer) state.get("returned_quantity")).isEqualTo(1);
        assertThat((Integer) state.get("pending_return_quantity")).isZero();
        assertThat(state.get("returned_at")).isNotNull();
        // 반품 사유는 보존됨
        assertThat(state.get("return_reason")).isEqualTo("WRONG_ITEM");

        // 재고 복구 확인
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stockAfter).isEqualTo(stockBefore + 1);

        // 환불 금액 기록 확인
        BigDecimal refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refunded).isGreaterThan(BigDecimal.ZERO);
    }

    // ====================================================================
    // 반품 신청 → 거절 플로우
    // ====================================================================

    /**
     * 관리자 거절 시 상태가 RETURN_REJECTED로 전이되고 거절 사유가 기록되며,
     * 재고/환불 변경이 없는지 확인한다.
     */
    @Test
    @DisplayName("반품 거절 — 상태 RETURN_REJECTED, 거절 사유 기록, 재고/환불 변경 없음")
    void requestThenReject_noRefundAndReasonRecorded() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());
        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        // When: 신청 → 거절
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "CHANGE_OF_MIND");
        orderService.rejectReturn(order.getOrderId(), itemId, "상품 사용 흔적이 확인되었습니다.");

        // Then: 상태 = RETURN_REJECTED
        Map<String, Object> state = getOrderItemState(itemId);
        assertThat(state.get("status")).isEqualTo("RETURN_REJECTED");
        assertThat(state.get("reject_reason")).isEqualTo("상품 사용 흔적이 확인되었습니다.");
        // 대기 수량 원복 → 잔여 수량 복원
        assertThat((Integer) state.get("pending_return_quantity")).isZero();
        // 환불 없음
        assertThat((Integer) state.get("returned_quantity")).isZero();

        // 재고 변화 없음
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stockAfter).isEqualTo(stockBefore);

        // 주문 환불 금액 없음
        BigDecimal refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refunded).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ====================================================================
    // 거절 후 재신청 → 승인 플로우
    // ====================================================================

    /**
     * [Step 4 UI 재신청 폼 검증] 거절된 아이템에 대해 재신청이 가능하고,
     * 재신청 후 승인 시 최종 환불이 정확한지 확인한다.
     *
     * <p>order/detail.html에서 RETURN_REJECTED 상태 아이템에
     * 재신청 폼이 표시되는데, 이 폼을 통한 재신청이 서비스 레벨에서도
     * 올바르게 동작하는지 검증한다.</p>
     */
    @Test
    @DisplayName("거절 후 재신청 → 승인 — 최종 상태 RETURNED, 환불 처리 완료")
    void rejectThenReRequest_thenApprove_fullFlow() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        // When: 신청 → 거절 → 재신청 → 승인
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "CHANGE_OF_MIND");
        orderService.rejectReturn(order.getOrderId(), itemId, "사진 증빙이 필요합니다.");

        // 재신청: 다른 사유로 재시도
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // 재신청 상태 확인
        String statusAfterReRequest = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(statusAfterReRequest).isEqualTo("RETURN_REQUESTED");

        // 재신청 사유가 업데이트 되었는지
        String updatedReason = jdbcTemplate.queryForObject(
                "SELECT return_reason FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(updatedReason).isEqualTo("DEFECT");

        // 승인
        orderService.approveReturn(order.getOrderId(), itemId);

        // Then: 최종 상태 RETURNED
        Map<String, Object> state = getOrderItemState(itemId);
        assertThat(state.get("status")).isEqualTo("RETURNED");
        assertThat((Integer) state.get("returned_quantity")).isEqualTo(1);

        // 환불 처리 완료
        BigDecimal refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refunded).isGreaterThan(BigDecimal.ZERO);
    }

    // ====================================================================
    // 상태 기반 폼 노출 조건 검증
    // ====================================================================

    /**
     * RETURN_REQUESTED 상태 아이템에 반품 재신청 시 BusinessException이 발생하는지 확인.
     *
     * <p>UI에서는 item.status.name() == 'NORMAL' 조건으로 폼을 숨기지만,
     * 서버에서도 상태 전이 규칙으로 이중 방어한다.</p>
     */
    @Test
    @DisplayName("RETURN_REQUESTED 아이템에 중복 반품 신청 → BusinessException")
    void requestReturnOnPendingItem_throwsException() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // When & Then: 동일 아이템에 또 반품 신청하면 상태 전이 실패
        // 잔여 수량이 1개 남아있지만, RETURN_REQUESTED 상태이므로 전이 불가
        // (실제로는 remainingQuantity가 1이므로 수량 검증은 통과하지만,
        //  status가 RETURN_REQUESTED이므로 RETURN_REQUESTED로의 전이가 불가)
        assertThatThrownBy(() ->
                orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "OTHER"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * RETURNED(종결) 상태 아이템에 반품 신청 시 BusinessException이 발생하는지 확인.
     */
    @Test
    @DisplayName("RETURNED 아이템에 반품 신청 → BusinessException (종결 상태)")
    void requestReturnOnReturnedItem_throwsException() {
        // Given: 신청 → 승인 완료
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");
        orderService.approveReturn(order.getOrderId(), itemId);

        // When & Then: RETURNED 상태에서 추가 반품 신청 불가
        // remainingQuantity = 2 - 0(cancelled) - 1(returned) - 0(pending) = 1
        // 수량은 있지만 상태가 RETURNED이므로 전이 불가
        assertThatThrownBy(() ->
                orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "OTHER"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 반품 승인 후 PointHistory에 RETURN 타입 환불 기록이 생성되는지 확인한다.
     */
    @Test
    @DisplayName("포인트 사용 주문에서 반품 승인 — PointHistory RETURN REFUND 기록 생성")
    void approveReturn_withPoints_createsPointHistory() {
        // Given: 포인트 부여 후 포인트 사용 주문
        int grantPoints = 3000;
        jdbcTemplate.update(
                "UPDATE users SET point_balance = point_balance + ? WHERE user_id = ?",
                grantPoints, testUserId);

        addCartItem(2);
        int usePoints = 1000;
        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, usePoints, null);
        Order order = orderService.createOrder(testUserId, request);
        createdOrderIds.add(order.getOrderId());

        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        Long itemId = findOrderItemId(order.getOrderId());

        // When: 반품 신청 → 승인
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "SIZE_ISSUE");
        orderService.approveReturn(order.getOrderId(), itemId);

        // Then: PointHistory에 RETURN REFUND 기록 존재
        int refundHistoryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM point_history
                WHERE user_id = ? AND reference_id = ?
                  AND change_type = 'REFUND' AND reference_type = 'RETURN'
                """,
                Integer.class, testUserId, order.getOrderId());
        assertThat(refundHistoryCount).isEqualTo(1);
    }

    // ====================================================================
    // 관리자 반품 대기 목록 조회 (UI 데이터 기반)
    // ====================================================================

    /**
     * 반품 신청 후 관리자 반품 대기 목록에 해당 건이 포함되는지 확인한다.
     * admin/returns.html 페이지에 표시될 데이터가 올바르게 조회되는지 검증한다.
     */
    @Test
    @DisplayName("반품 신청 후 관리자 반품 대기 목록에 포함된다")
    void requestReturn_appearsInPendingReturnList() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        // When
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // Then: 대기 목록에 포함
        long pendingCount = orderService.getPendingReturnCount();
        assertThat(pendingCount).isGreaterThanOrEqualTo(1);

        // 승인 후에는 대기 목록에서 제거
        orderService.approveReturn(order.getOrderId(), itemId);

        // 해당 아이템이 더 이상 RETURN_REQUESTED가 아닌지 확인
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(status).isNotEqualTo("RETURN_REQUESTED");
    }
}

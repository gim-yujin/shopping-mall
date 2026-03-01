package com.shop.domain.order.service;

import com.shop.domain.order.dto.AdminReturnResponse;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * [Step 5] 관리자 반품 관리 워크플로우 통합 테스트.
 *
 * <h3>검증 범위</h3>
 *
 * <p>Step 5에서 추가된 관리자 UI의 데이터 흐름을 end-to-end로 검증한다.
 * AdminController → OrderService → OrderQueryService → OrderItemRepository 경로와
 * AdminController → OrderService → PartialCancellationService 경로가 모두 포함된다.</p>
 *
 * <h4>대시보드 반품 대기 카드</h4>
 * <ul>
 *   <li>반품 신청 전/후 pendingReturnCount 변화 확인</li>
 *   <li>승인 후 pendingReturnCount 감소 확인</li>
 * </ul>
 *
 * <h4>반품 대기 목록 (GET /admin/returns)</h4>
 * <ul>
 *   <li>반품 신청 후 목록에 해당 건 포함, 반품 사유 정확성</li>
 *   <li>승인/거절 후 목록에서 제거</li>
 *   <li>사용자 정보(이름/이메일) 포함 확인</li>
 * </ul>
 *
 * <h4>반품 승인 (POST /admin/returns/{id}/approve)</h4>
 * <ul>
 *   <li>승인 후 상태 RETURNED, 재고 복구, 환불 금액 기록</li>
 * </ul>
 *
 * <h4>반품 거절 (POST /admin/returns/{id}/reject)</h4>
 * <ul>
 *   <li>거절 후 상태 RETURN_REJECTED, 거절 사유 기록, 재고/환불 변경 없음</li>
 * </ul>
 *
 * <h4>거절 후 재신청 → 목록 재포함</h4>
 * <ul>
 *   <li>거절된 아이템 재신청 시 목록에 재등장, 최종 승인까지 전체 플로우</li>
 * </ul>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class AdminReturnManagementIntegrationTest {

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

    // ====================================================================
    // 대시보드 반품 대기 카드
    // ====================================================================

    /**
     * [Step 5 핵심 검증] 반품 신청/승인에 따라 pendingReturnCount가 정확히 변화하는지 확인.
     *
     * <p>대시보드에서 호출하는 OrderService.getPendingReturnCount()가 실시간으로
     * 정확한 값을 반환하는지 검증한다. 이 값이 부정확하면 관리자가 미처리 건을
     * 놓치거나 불필요한 페이지 접근을 하게 된다.</p>
     */
    @Test
    @DisplayName("pendingReturnCount — 신청 시 증가, 승인 시 감소")
    void pendingReturnCount_incrementsAndDecrementsCorrectly() {
        // Given
        long countBefore = orderService.getPendingReturnCount();
        Order order = createDeliveredOrder(3);
        Long itemId = findOrderItemId(order.getOrderId());

        // When: 반품 신청
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // Then: 건수 증가
        long countAfterRequest = orderService.getPendingReturnCount();
        assertThat(countAfterRequest).isEqualTo(countBefore + 1);

        // When: 승인
        orderService.approveReturn(order.getOrderId(), itemId);

        // Then: 건수 원복
        long countAfterApprove = orderService.getPendingReturnCount();
        assertThat(countAfterApprove).isEqualTo(countBefore);
    }

    // ====================================================================
    // 반품 대기 목록 조회
    // ====================================================================

    /**
     * [Step 5 핵심 검증] 반품 신청 건이 관리자 반품 대기 목록에 정확한 정보로 포함되는지 확인.
     *
     * <p>AdminReturnResponse의 각 필드(주문번호, 상품명, 수량, 사유, 사용자 정보)가
     * 올바르게 매핑되는지 검증한다. 이 데이터가 admin/returns.html 테이블에 바인딩된다.</p>
     */
    @Test
    @DisplayName("반품 대기 목록 — 신청 건 포함, DTO 필드 정확성 검증")
    void getReturnRequests_containsRequestWithCorrectFields() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "WRONG_ITEM");

        // When
        Page<AdminReturnResponse> page = orderService.getReturnRequests(0);

        // Then: 목록에 해당 건 포함
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);

        Optional<AdminReturnResponse> found = page.getContent().stream()
                .filter(r -> r.orderItemId().equals(itemId))
                .findFirst();
        assertThat(found).isPresent();

        AdminReturnResponse ret = found.get();
        assertThat(ret.orderId()).isEqualTo(order.getOrderId());
        assertThat(ret.orderNumber()).isNotBlank();
        assertThat(ret.productName()).isNotBlank();
        assertThat(ret.quantity()).isEqualTo(1);
        assertThat(ret.returnReason()).isEqualTo("WRONG_ITEM");
        assertThat(ret.returnReasonLabel()).isEqualTo("오배송");
        assertThat(ret.returnRequestedAt()).isNotNull();
        assertThat(ret.userName()).isNotBlank();
    }

    /**
     * 승인 후 반품 대기 목록에서 해당 건이 제거되는지 확인.
     */
    @Test
    @DisplayName("반품 승인 후 — 대기 목록에서 제거됨")
    void approvedReturn_removedFromPendingList() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // When: 승인
        orderService.approveReturn(order.getOrderId(), itemId);

        // Then: 목록에서 제거
        Page<AdminReturnResponse> page = orderService.getReturnRequests(0);
        boolean stillPresent = page.getContent().stream()
                .anyMatch(r -> r.orderItemId().equals(itemId));
        assertThat(stillPresent).isFalse();
    }

    /**
     * 거절 후 반품 대기 목록에서 해당 건이 제거되는지 확인.
     */
    @Test
    @DisplayName("반품 거절 후 — 대기 목록에서 제거됨")
    void rejectedReturn_removedFromPendingList() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "CHANGE_OF_MIND");

        // When: 거절
        orderService.rejectReturn(order.getOrderId(), itemId, "반품 기간 초과");

        // Then: 목록에서 제거
        Page<AdminReturnResponse> page = orderService.getReturnRequests(0);
        boolean stillPresent = page.getContent().stream()
                .anyMatch(r -> r.orderItemId().equals(itemId));
        assertThat(stillPresent).isFalse();
    }

    // ====================================================================
    // 반품 승인 — 재고/환불 처리 확인
    // ====================================================================

    /**
     * [Step 5 핵심 검증] 관리자 반품 승인 시 재고 복구와 환불이 실행되는지 확인.
     *
     * <p>admin/returns.html의 승인 버튼 → AdminController.approveReturn()
     * → OrderService.approveReturn() → PartialCancellationService.approveReturn()
     * 경로가 올바르게 동작하여 재고가 복구되고 환불 금액이 기록되는지 검증한다.</p>
     */
    @Test
    @DisplayName("반품 승인 — 재고 복구 + 환불 금액 기록")
    void approveReturn_restoresStockAndRecordsRefund() {
        // Given
        Order order = createDeliveredOrder(3);
        Long itemId = findOrderItemId(order.getOrderId());

        int stockBeforeReturn = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 2, "DEFECT");

        // 신청 단계에서는 재고 변화 없음
        int stockAfterRequest = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stockAfterRequest).isEqualTo(stockBeforeReturn);

        // When: 승인
        orderService.approveReturn(order.getOrderId(), itemId);

        // Then: 재고 복구
        int stockAfterApprove = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stockAfterApprove).isEqualTo(stockBeforeReturn + 2);

        // 환불 금액 기록
        BigDecimal refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refunded).isGreaterThan(BigDecimal.ZERO);

        // 아이템 상태 RETURNED
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(status).isEqualTo("RETURNED");
    }

    // ====================================================================
    // 반품 거절 — 재고/환불 미변경 확인
    // ====================================================================

    /**
     * 관리자 반품 거절 시 거절 사유가 기록되고, 재고/환불에 변화가 없는지 확인.
     */
    @Test
    @DisplayName("반품 거절 — 거절 사유 기록, 재고/환불 미변경")
    void rejectReturn_recordsReasonWithoutStockOrRefundChange() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);

        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "SIZE_ISSUE");

        // When: 거절
        String rejectReason = "교환으로 처리해 주세요. 반품 불가합니다.";
        orderService.rejectReturn(order.getOrderId(), itemId, rejectReason);

        // Then: 거절 사유 기록
        String savedReason = jdbcTemplate.queryForObject(
                "SELECT reject_reason FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(savedReason).isEqualTo(rejectReason);

        // 상태 RETURN_REJECTED
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(status).isEqualTo("RETURN_REJECTED");

        // 재고 변화 없음
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stockAfter).isEqualTo(stockBefore);

        // 환불 없음
        BigDecimal refunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refunded).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ====================================================================
    // 거절 후 재신청 → 목록 재포함 → 최종 승인
    // ====================================================================

    /**
     * [Step 5 핵심 검증] 거절 후 재신청 시 반품 대기 목록에 재등장하고,
     * 최종 승인까지 전체 워크플로우가 동작하는지 확인.
     *
     * <p>사용자가 order/detail.html의 재신청 폼으로 반품을 재신청하면,
     * 관리자의 admin/returns.html 목록에 다시 나타나야 한다.</p>
     */
    @Test
    @DisplayName("거절 → 재신청 → 목록 재포함 → 승인 — 전체 플로우")
    void rejectThenReRequest_reappearsInList_thenApprove() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        // 1차: 신청 → 거절
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "CHANGE_OF_MIND");
        orderService.rejectReturn(order.getOrderId(), itemId, "사진 증빙 필요");

        // 거절 후 목록에서 제거 확인
        assertThat(isInPendingList(itemId)).isFalse();

        // 2차: 재신청
        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");

        // 목록에 재등장 확인
        assertThat(isInPendingList(itemId)).isTrue();

        // 재신청 사유 업데이트 확인
        Page<AdminReturnResponse> page = orderService.getReturnRequests(0);
        Optional<AdminReturnResponse> found = page.getContent().stream()
                .filter(r -> r.orderItemId().equals(itemId))
                .findFirst();
        assertThat(found).isPresent();
        assertThat(found.get().returnReason()).isEqualTo("DEFECT");

        // 최종 승인
        orderService.approveReturn(order.getOrderId(), itemId);

        // 목록에서 최종 제거
        assertThat(isInPendingList(itemId)).isFalse();

        // 상태 RETURNED
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, itemId);
        assertThat(finalStatus).isEqualTo("RETURNED");
    }

    // ====================================================================
    // 이미 처리된 아이템 중복 처리 방지
    // ====================================================================

    /**
     * 이미 승인된 아이템에 대해 다시 승인 시도하면 예외가 발생하는지 확인.
     * 관리자가 실수로 동일 건을 두 번 승인하는 것을 방지한다.
     */
    @Test
    @DisplayName("이미 승인된 아이템 재승인 시도 → BusinessException")
    void approveAlreadyApproved_throwsException() {
        // Given
        Order order = createDeliveredOrder(2);
        Long itemId = findOrderItemId(order.getOrderId());

        orderService.requestReturn(order.getOrderId(), testUserId, itemId, 1, "DEFECT");
        orderService.approveReturn(order.getOrderId(), itemId);

        // When & Then: 이미 RETURNED 상태이므로 재승인 불가
        assertThatThrownBy(() ->
                orderService.approveReturn(order.getOrderId(), itemId))
                .isInstanceOf(BusinessException.class);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────

    private boolean isInPendingList(Long orderItemId) {
        Page<AdminReturnResponse> page = orderService.getReturnRequests(0);
        return page.getContent().stream()
                .anyMatch(r -> r.orderItemId().equals(orderItemId));
    }
}

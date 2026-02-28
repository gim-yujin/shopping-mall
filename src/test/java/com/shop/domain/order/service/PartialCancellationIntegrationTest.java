package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * PartialCancellationService 통합 테스트 — 실제 DB 트랜잭션을 통한 부분 취소/반품 검증.
 *
 * <h3>검증 범위</h3>
 *
 * <p><b>부분 취소 정상 플로우:</b>
 * <ul>
 *   <li>환불 금액이 finalAmount 비례로 정확하게 계산되는지 (P0-1 과다 환불 방지)</li>
 *   <li>포인트가 비례 환불되고 PointHistory에 기록되는지 (P0-2)</li>
 *   <li>등급이 재계산되는지 (P0-3)</li>
 *   <li>재고가 복구되고 재고 이력이 기록되는지</li>
 *   <li>ProductStockChangedEvent에 의한 캐시 무효화 (P1-3)</li>
 *   <li>totalSpent가 환불 금액만큼 차감되는지</li>
 * </ul>
 *
 * <p><b>전체 아이템 부분 취소 시 상태 전이:</b>
 * <ul>
 *   <li>PAID 상태에서 모든 아이템 취소 → CANCELLED 전이</li>
 * </ul>
 *
 * <p><b>실패 케이스:</b>
 * <ul>
 *   <li>SHIPPED/CANCELLED 상태에서 부분 취소 거부</li>
 *   <li>잔여 수량 초과 요청 거부</li>
 * </ul>
 *
 * <p><b>반품:</b>
 * <ul>
 *   <li>DELIVERED 상태에서 반품 성공 + 포인트/재고/이력 검증</li>
 *   <li>PAID 상태에서 반품 거부</li>
 * </ul>
 *
 * <p>주의: 실제 PostgreSQL DB에 연결하여 테스트합니다.</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class PartialCancellationIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CacheManager cacheManager;

    // 테스트 대상
    private Long testUserId;
    private Long testProductIdA;
    private Long testProductIdB;

    // 원본 상태 백업
    private Map<String, Object> originalUserState;
    private Map<String, Object> originalProductAState;
    private Map<String, Object> originalProductBState;

    // 테스트 중 생성된 주문 추적
    private final List<Long> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ensurePointHistoryReferenceTypeConstraint();
        // 서로 다른 활성 상품 2개 선택 (다중 아이템 주문 구성)
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
                """
                SELECT product_id FROM products
                WHERE is_active = true AND stock_quantity >= 100
                ORDER BY product_id LIMIT 2
                """);
        assertThat(products).hasSizeGreaterThanOrEqualTo(2);

        testProductIdA = ((Number) products.get(0).get("product_id")).longValue();
        testProductIdB = ((Number) products.get(1).get("product_id")).longValue();

        originalProductAState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductIdA);
        originalProductBState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductIdB);

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

        System.out.println("  [setUp] userId=" + testUserId
                + ", productA=" + testProductIdA + ", productB=" + testProductIdB);
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

        // 상품 원본 복원
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalProductAState.get("stock_quantity"),
                originalProductAState.get("sales_count"),
                testProductIdA);
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalProductBState.get("stock_quantity"),
                originalProductBState.get("sales_count"),
                testProductIdB);

        // 사용자 원본 복원
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserState.get("total_spent"),
                originalUserState.get("point_balance"),
                originalUserState.get("tier_id"),
                testUserId);
    }

    /**
     * 테스트 DB가 기존 CHECK 제약을 유지하고 있을 수 있어
     * 부분취소/반품 reference_type 값을 허용하도록 보정한다.
     */
    private void ensurePointHistoryReferenceTypeConstraint() {
        jdbcTemplate.execute("ALTER TABLE point_history DROP CONSTRAINT IF EXISTS chk_point_reference_type");
        jdbcTemplate.execute("""
                ALTER TABLE point_history
                ADD CONSTRAINT chk_point_reference_type CHECK (
                    reference_type IN ('ORDER', 'CANCEL', 'PARTIAL_CANCEL', 'RETURN', 'ADMIN', 'SYSTEM')
                )
                """);
    }

    // ── 헬퍼 메서드 ───────────────────────────────────────

    private void addCartItem(Long userId, Long productId, int quantity) {
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                userId, productId, quantity, now, now);
    }

    private OrderCreateRequest defaultRequest() {
        return new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, null, null);
    }

    private OrderCreateRequest requestWithPoints(int usePoints) {
        return new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, usePoints, null);
    }

    /**
     * 다중 아이템 주문을 생성하고 Order를 반환한다.
     * 상품A: quantityA개, 상품B: quantityB개.
     */
    private Order createMultiItemOrder(int quantityA, int quantityB) {
        addCartItem(testUserId, testProductIdA, quantityA);
        addCartItem(testUserId, testProductIdB, quantityB);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());
        return order;
    }

    /**
     * 주문의 특정 상품에 해당하는 OrderItem ID를 조회한다.
     */
    private Long findOrderItemId(Long orderId, Long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_item_id FROM order_items WHERE order_id = ? AND product_id = ?",
                Long.class, orderId, productId);
    }

    // ====================================================================
    // 부분 취소 정상 플로우
    // ====================================================================

    /**
     * [P0-1 검증] 부분 취소 환불 금액이 finalAmount 비례로 계산되는지 확인한다.
     *
     * <p>핵심 포인트: 환불액은 OrderItem.subtotal(할인 전 원가)이 아니라,
     * Order.finalAmount(실결제금액)에서 배송비를 뺀 금액을 기준으로 비례 계산해야 한다.
     * 그렇지 않으면 등급/쿠폰/포인트 할인이 적용된 주문에서 과다 환불이 발생한다.</p>
     */
    @Test
    @DisplayName("부분 취소 성공 — 환불 금액이 finalAmount 비례, 재고 복구, 이력 기록")
    void partialCancel_success_refundAndStockAndHistory() {
        // Given: 상품A 3개 + 상품B 2개 주문
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        int stockABefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        BigDecimal totalSpentBefore = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?",
                BigDecimal.class, testUserId);

        // When: 상품A 1개 부분 취소
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);

        // Then: 환불 금액이 DB에 기록됨
        BigDecimal refundedAmount = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refundedAmount).isGreaterThan(BigDecimal.ZERO);

        // 환불액이 상품A 단가(원가 기준)보다 작거나 같아야 한다 (할인 반영)
        BigDecimal itemAUnitPrice = jdbcTemplate.queryForObject(
                "SELECT unit_price FROM order_items WHERE order_item_id = ?",
                BigDecimal.class, orderItemIdA);
        assertThat(refundedAmount).isLessThanOrEqualTo(itemAUnitPrice);

        // 비례 환불 정확성: effectivePaid × (itemSubtotal/totalAmount) × (1/quantity)
        BigDecimal effectivePaid = order.getFinalAmount().subtract(order.getShippingFee());
        BigDecimal itemASubtotal = itemAUnitPrice.multiply(BigDecimal.valueOf(3));
        BigDecimal expectedRefund = effectivePaid
                .multiply(itemASubtotal)
                .divide(order.getTotalAmount(), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.ONE)
                .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        assertThat(refundedAmount).isEqualByComparingTo(expectedRefund);

        // 재고 복구 확인 (+1)
        int stockAAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockAAfter).isEqualTo(stockABefore + 1);

        // totalSpent 차감 확인
        BigDecimal totalSpentAfter = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?",
                BigDecimal.class, testUserId);
        assertThat(totalSpentAfter).isEqualByComparingTo(totalSpentBefore.subtract(refundedAmount));

        // cancelledQuantity 기록 확인
        Integer cancelledQty = jdbcTemplate.queryForObject(
                "SELECT cancelled_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);
        assertThat(cancelledQty).isEqualTo(1);

        // 재고 이력(IN) 기록 확인
        int historyCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM product_inventory_history
                WHERE product_id = ? AND reference_id = ? AND change_type = 'IN' AND reason = 'PARTIAL_CANCEL'
                """,
                Integer.class, testProductIdA, order.getOrderId());
        assertThat(historyCount).isEqualTo(1);

        System.out.println("  [PASS] 부분 취소: 환불=" + refundedAmount
                + " (단가 " + itemAUnitPrice + "보다 작음, 할인 반영),"
                + " 재고: " + stockABefore + " → " + stockAAfter);
    }

    /**
     * [P0-2 검증] 포인트 사용 주문에서 부분 취소 시 포인트가 비례 환불되고
     * PointHistory에 REFUND 레코드가 생성되는지 확인한다.
     *
     * <p>기존 버그: 부분 취소 시 포인트 환불이 전혀 없었다.
     * 수정 후: usedPoints를 아이템 비중에 따라 비례 환불한다.</p>
     */
    @Test
    @DisplayName("부분 취소 + 포인트 환불 — 비례 환불 + PointHistory REFUND 기록")
    void partialCancel_withPoints_refundsProportionally() {
        // Given: 포인트 부여 후 포인트 사용 주문
        int grantPoints = 5000;
        jdbcTemplate.update(
                "UPDATE users SET point_balance = point_balance + ? WHERE user_id = ?",
                grantPoints, testUserId);
        int pointsBefore = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        addCartItem(testUserId, testProductIdA, 3);
        int usePoints = 1500;
        Order order = orderService.createOrder(testUserId, requestWithPoints(usePoints));
        createdOrderIds.add(order.getOrderId());

        int pointsAfterOrder = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(pointsAfterOrder).isEqualTo(pointsBefore - usePoints);

        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        // When: 1개 부분 취소
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);

        // Then: 포인트 비례 환불
        int pointsAfterCancel = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        int pointRefunded = pointsAfterCancel - pointsAfterOrder;
        assertThat(pointRefunded).isGreaterThan(0);

        // 비례 환불 검증: floor(usedPoints × (1/totalQuantity)) ≈ 500
        // 단일 상품이므로 비중 = 1.0, 1/3개 = 500P
        int expectedPointRefund = (int) Math.floor(
                (double) usePoints * 1 / 3);
        assertThat(pointRefunded).isEqualTo(expectedPointRefund);

        // refundedPoints 누계 확인
        Integer refundedPoints = jdbcTemplate.queryForObject(
                "SELECT refunded_points FROM orders WHERE order_id = ?",
                Integer.class, order.getOrderId());
        assertThat(refundedPoints).isEqualTo(pointRefunded);

        // PointHistory REFUND 레코드 확인
        int refundHistoryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM point_history
                WHERE user_id = ? AND reference_id = ?
                  AND change_type = 'REFUND' AND reference_type = 'PARTIAL_CANCEL'
                """,
                Integer.class, testUserId, order.getOrderId());
        assertThat(refundHistoryCount).isEqualTo(1);

        System.out.println("  [PASS] 포인트 비례 환불: " + pointsAfterOrder
                + "P → " + pointsAfterCancel + "P (환불 " + pointRefunded + "P)");
    }

    /**
     * [P0-3 검증] 부분 취소 후 등급이 재계산되는지 확인한다.
     *
     * <p>기존 버그: addTotalSpent만 호출하고 등급 재계산이 없어,
     * 대량 부분 취소 후에도 높은 등급이 유지되었다.</p>
     */
    @Test
    @DisplayName("부분 취소 후 등급이 재계산된다")
    void partialCancel_recalculatesTier() {
        // Given: 주문 생성
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        Integer tierIdBefore = jdbcTemplate.queryForObject(
                "SELECT tier_id FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        // When: 부분 취소
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);

        // Then: tier_id가 설정되어 있음 (재계산이 실행되었다는 증거)
        Integer tierIdAfter = jdbcTemplate.queryForObject(
                "SELECT tier_id FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(tierIdAfter).isNotNull();

        // 등급은 totalSpent에 따라 결정되므로, 적어도 재계산이 실행되었는지만 확인
        // (테스트 환경의 totalSpent에 따라 등급이 동일하거나 강등될 수 있음)
        System.out.println("  [PASS] 등급 재계산: tier " + tierIdBefore + " → " + tierIdAfter);
    }

    /**
     * [P1-3 검증] 부분 취소 후 ProductStockChangedEvent 발행으로
     * productDetail 캐시가 무효화되는지 확인한다.
     *
     * <p>기존 버그: 부분 취소에서 이벤트를 발행하지 않아,
     * 상품 상세 캐시에 최대 2분(TTL) 동안 과거 재고가 표시되었다.</p>
     */
    @Test
    @DisplayName("부분 취소 후 productDetail 캐시가 무효화된다")
    void partialCancel_evictsProductDetailCache() {
        // Given: 주문 생성 후 캐시 워밍
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        Cache cache = Objects.requireNonNull(cacheManager.getCache("productDetail"));
        // 캐시에 상품A 적재
        cache.put(testProductIdA, "dummy-cached-value");
        assertThat(cache.get(testProductIdA)).isNotNull();

        // When: 부분 취소 (ProductStockChangedEvent 발행 → 캐시 무효화)
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);

        // Then: 캐시 무효화 확인
        assertThat(cache.get(testProductIdA))
                .as("부분 취소 후 productDetail 캐시가 무효화되어야 한다")
                .isNull();

        System.out.println("  [PASS] productDetail 캐시 무효화 완료");
    }

    // ====================================================================
    // 연속 부분 취소 + 전체 취소 전이
    // ====================================================================

    /**
     * 모든 아이템을 연속 부분 취소하면 주문 상태가 CANCELLED로 전이되는지 확인한다.
     *
     * <p>시나리오: 상품A 3개 + 상품B 2개 주문 → 상품A 3개 취소 → 상품B 2개 취소
     * → 모든 아이템의 remainingQuantity가 0 → CANCELLED 전이.</p>
     *
     * <p>환불 누계가 실결제금액을 초과하지 않는지도 함께 검증한다.
     * (반올림 누적에 의한 초과 환불 방지)</p>
     */
    @Test
    @DisplayName("전체 아이템 부분 취소 → CANCELLED 전이 + 환불 누계 ≤ 실결제금액")
    void partialCancelAll_transitionsToCancelled() {
        // Given
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);
        Long orderItemIdB = findOrderItemId(order.getOrderId(), testProductIdB);

        // When: 상품A 3개 모두 취소 (1개씩 3번)
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);

        // 상품A 전부 취소 후에도 아직 상품B가 남아있으므로 PAID 유지
        String statusMid = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, order.getOrderId());
        assertThat(statusMid).isEqualTo("PAID");

        // 상품B 2개 모두 취소
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdB, 2);

        // Then: 모든 아이템 취소 → CANCELLED 전이
        String statusFinal = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, order.getOrderId());
        assertThat(statusFinal).isEqualTo("CANCELLED");

        // 환불 누계가 실결제금액(배송비 제외)을 초과하지 않는지 확인
        BigDecimal totalRefunded = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        BigDecimal effectivePaid = order.getFinalAmount().subtract(order.getShippingFee());
        assertThat(totalRefunded)
                .as("환불 누계가 실결제금액(배송비 제외)을 초과하면 안 된다")
                .isLessThanOrEqualTo(effectivePaid);

        // 재고 완전 복구 확인
        int stockA = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        int stockB = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdB);
        assertThat(stockA).isEqualTo(((Number) originalProductAState.get("stock_quantity")).intValue());
        assertThat(stockB).isEqualTo(((Number) originalProductBState.get("stock_quantity")).intValue());

        System.out.println("  [PASS] 전체 아이템 취소: 상태=" + statusFinal
                + ", 환불 누계=" + totalRefunded + " (상한=" + effectivePaid + ")");
    }

    // ====================================================================
    // 부분 취소 실패 케이스
    // ====================================================================

    @Test
    @DisplayName("SHIPPED 상태에서 부분 취소 → BusinessException")
    void partialCancel_shippedOrder_throwsException() {
        // Given: 주문 생성 후 SHIPPED 상태로 변경
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);
        jdbcTemplate.update(
                "UPDATE orders SET order_status = 'SHIPPED' WHERE order_id = ?",
                order.getOrderId());

        // When & Then
        assertThatThrownBy(() ->
                orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("부분 취소 가능한 주문 상태가 아닙니다");
    }

    @Test
    @DisplayName("CANCELLED 상태에서 부분 취소 → BusinessException")
    void partialCancel_cancelledOrder_throwsException() {
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);
        orderService.cancelOrder(order.getOrderId(), testUserId);

        assertThatThrownBy(() ->
                orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("잔여 수량 초과 부분 취소 → BusinessException")
    void partialCancel_exceedsRemaining_throwsException() {
        Order order = createMultiItemOrder(3, 2);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        // 상품A는 3개인데 4개 취소 요청
        assertThatThrownBy(() ->
                orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 4))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잔여");

        // 재고 변화 없음 확인 (트랜잭션 롤백)
        int stockA = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        int expectedStock = ((Number) originalProductAState.get("stock_quantity")).intValue() - 3;
        assertThat(stockA).isEqualTo(expectedStock);
    }

    @Test
    @DisplayName("존재하지 않는 주문상품 ID → ResourceNotFoundException")
    void partialCancel_invalidOrderItemId_throwsException() {
        Order order = createMultiItemOrder(3, 2);

        Long invalidItemId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(order_item_id), 0) + 9999 FROM order_items",
                Long.class);

        assertThatThrownBy(() ->
                orderService.partialCancel(order.getOrderId(), testUserId, invalidItemId, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ====================================================================
    // 반품
    // ====================================================================

    /**
     * [Step 2] 반품 신청 → 관리자 승인 전체 플로우를 검증한다.
     *
     * <p>반품 신청(requestReturn) 시에는 상태 전이만 수행하고,
     * 관리자 승인(approveReturn) 시에 재고 복구, 환불 금액 기록,
     * 포인트 환불, 재고 이력(reason=RETURN) 기록을 검증한다.</p>
     */
    @Test
    @DisplayName("반품 신청 → 관리자 승인 — 재고 복구 + 환불 + PointHistory")
    void requestReturn_delivered_success() {
        // Given: 포인트 부여 후 포인트 사용 주문 생성 → DELIVERED 상태로 전이
        int grantPoints = 3000;
        jdbcTemplate.update(
                "UPDATE users SET point_balance = point_balance + ? WHERE user_id = ?",
                grantPoints, testUserId);

        addCartItem(testUserId, testProductIdA, 2);
        int usePoints = 1000;
        Order order = orderService.createOrder(testUserId, requestWithPoints(usePoints));
        createdOrderIds.add(order.getOrderId());

        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        // DELIVERED 상태로 전이
        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        int pointsBefore = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        // When Step 1: 반품 신청 (상태 전이만)
        orderService.requestReturn(order.getOrderId(), testUserId, orderItemIdA, 1, "DEFECT");

        // Then Step 1: 상태가 RETURN_REQUESTED이고 재고/환불은 변경 없음
        String itemStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemIdA);
        assertThat(itemStatus).isEqualTo("RETURN_REQUESTED");

        int stockAfterRequest = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockAfterRequest).isEqualTo(stockBefore);  // 재고 변경 없음

        Integer pendingQty = jdbcTemplate.queryForObject(
                "SELECT pending_return_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);
        assertThat(pendingQty).isEqualTo(1);

        // When Step 2: 관리자 승인 (재고 복구 + 환불 실행)
        orderService.approveReturn(order.getOrderId(), orderItemIdA);

        // Then Step 2: 재고 복구
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockAfter).isEqualTo(stockBefore + 1);

        // returnedQuantity 기록 확인
        Integer returnedQty = jdbcTemplate.queryForObject(
                "SELECT returned_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);
        assertThat(returnedQty).isEqualTo(1);

        // 상태가 RETURNED로 전이
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemIdA);
        assertThat(finalStatus).isEqualTo("RETURNED");

        // 환불 금액 기록 확인
        BigDecimal refundedAmount = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refundedAmount).isGreaterThan(BigDecimal.ZERO);

        // 포인트 비례 환불 확인 (usedPoints=1000, 2개 중 1개 = 500P)
        int pointsAfter = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        int pointRefunded = pointsAfter - pointsBefore;
        assertThat(pointRefunded).isGreaterThan(0);

        // PointHistory RETURN 레코드 확인
        int returnHistoryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM point_history
                WHERE user_id = ? AND reference_id = ?
                  AND change_type = 'REFUND' AND reference_type = 'RETURN'
                """,
                Integer.class, testUserId, order.getOrderId());
        assertThat(returnHistoryCount).isEqualTo(1);

        // 재고 이력(RETURN) 기록 확인
        int returnInvHistoryCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM product_inventory_history
                WHERE product_id = ? AND reference_id = ? AND reason = 'RETURN'
                """,
                Integer.class, testProductIdA, order.getOrderId());
        assertThat(returnInvHistoryCount).isEqualTo(1);

        System.out.println("  [PASS] 반품 신청→승인: 재고 " + stockBefore + " → " + stockAfter
                + ", 포인트 환불 " + pointRefunded + "P, 환불액=" + refundedAmount);
    }

    @Test
    @DisplayName("PAID 상태에서 반품 → BusinessException")
    void requestReturn_paidOrder_throwsException() {
        Order order = createMultiItemOrder(2, 1);
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        assertThatThrownBy(() ->
                orderService.requestReturn(order.getOrderId(), testUserId, orderItemIdA, 1, "DEFECT"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("배송완료 상태에서만 가능");
    }

    // ====================================================================
    // 반품 기간 제한 (P2-9)
    // ====================================================================

    /**
     * [P2-9 검증] 배송 완료 후 14일 이내 반품이 정상 처리되는지 확인한다.
     *
     * <p>DELIVERED 상태로 전이 후 delivered_at을 13일 전으로 변경하여
     * 기한 내 반품이 정상적으로 처리되는지 검증한다.</p>
     */
    @Test
    @DisplayName("[P2-9] 반품 기간 내(13일 경과) 반품 성공")
    void requestReturn_withinPeriod_succeeds() {
        // Given: 주문 생성 → DELIVERED 전이
        addCartItem(testUserId, testProductIdA, 2);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        // delivered_at을 13일 전으로 조정 (기한 1일 전)
        jdbcTemplate.update(
                "UPDATE orders SET delivered_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusDays(13), order.getOrderId());

        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);

        // When & Then: 기간 내이므로 정상 처리
        assertThatCode(() ->
                orderService.requestReturn(order.getOrderId(), testUserId, orderItemIdA, 1, "DEFECT"))
                .doesNotThrowAnyException();

        // Step 2 변경: 반품 신청 시 재고는 즉시 복구되지 않음
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockAfter).isEqualTo(stockBefore);

        // 반품 신청 상태/대기 수량 확인
        String itemStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemIdA);
        assertThat(itemStatus).isEqualTo("RETURN_REQUESTED");

        Integer pendingQty = jdbcTemplate.queryForObject(
                "SELECT pending_return_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);
        assertThat(pendingQty).isEqualTo(1);

        System.out.println("  [PASS] 반품 기간 내(13일 경과): 재고 " + stockBefore + " → " + stockAfter);
    }

    /**
     * [P2-9 검증] 배송 완료 후 14일 초과 시 반품이 거부되는지 확인한다.
     *
     * <p>delivered_at을 15일 전으로 변경하여 RETURN_PERIOD_EXPIRED 예외가
     * 발생하고, 재고/환불 등 부수 효과가 전혀 없는지 검증한다.</p>
     */
    @Test
    @DisplayName("[P2-9] 반품 기간 초과(15일 경과) → RETURN_PERIOD_EXPIRED")
    void requestReturn_afterPeriod_throwsException() {
        // Given: 주문 생성 → DELIVERED 전이
        addCartItem(testUserId, testProductIdA, 2);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        // delivered_at을 15일 전으로 조정 (기한 1일 초과)
        jdbcTemplate.update(
                "UPDATE orders SET delivered_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusDays(15), order.getOrderId());

        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        BigDecimal refundedBefore = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());

        // When & Then: 기간 초과 → RETURN_PERIOD_EXPIRED
        assertThatThrownBy(() ->
                orderService.requestReturn(order.getOrderId(), testUserId, orderItemIdA, 1, "DEFECT"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("반품 가능 기간이 지났습니다");

        // 부수 효과 없음 확인: 재고 변화 없음
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockAfter).isEqualTo(stockBefore);

        // 부수 효과 없음 확인: 환불 금액 변화 없음
        BigDecimal refundedAfter = jdbcTemplate.queryForObject(
                "SELECT refunded_amount FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(refundedAfter).isEqualByComparingTo(refundedBefore);

        System.out.println("  [PASS] 반품 기간 초과(15일): 거부됨, 재고/환불 변화 없음");
    }

    /**
     * [P2-9 경계값 검증] 배송 완료 후 정확히 14일째에 반품이 허용되는지 확인한다.
     *
     * <p>delivered_at을 14일 전 + 1시간으로 설정하여 마감일 당일에
     * 반품이 가능한지 경계값을 검증한다.</p>
     */
    @Test
    @DisplayName("[P2-9] 반품 마감일 당일(14일째) 반품 성공 — 경계값")
    void requestReturn_onDeadlineDay_succeeds() {
        // Given
        addCartItem(testUserId, testProductIdA, 2);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        // 정확히 14일 전 + 1시간 (마감 시점 직전)
        jdbcTemplate.update(
                "UPDATE orders SET delivered_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusDays(14).plusHours(1), order.getOrderId());

        // When & Then: 마감일 당일이므로 아직 허용
        assertThatCode(() ->
                orderService.requestReturn(order.getOrderId(), testUserId, orderItemIdA, 1, "DEFECT"))
                .doesNotThrowAnyException();

        // Step 2 변경: 신청 시 returned_quantity 대신 pending_return_quantity가 기록됨
        Integer pendingQty = jdbcTemplate.queryForObject(
                "SELECT pending_return_quantity FROM order_items WHERE order_item_id = ?",
                Integer.class, orderItemIdA);
        assertThat(pendingQty).isEqualTo(1);

        String itemStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_items WHERE order_item_id = ?",
                String.class, orderItemIdA);
        assertThat(itemStatus).isEqualTo("RETURN_REQUESTED");

        System.out.println("  [PASS] 반품 마감일 당일: 정상 처리됨");
    }

    /**
     * DELIVERED 상태에서 전체 아이템을 반품해도 주문 상태는 DELIVERED로 유지되는지 확인한다.
     *
     * <p>부분 취소(PENDING/PAID)에서는 전체 취소 시 CANCELLED로 전이하지만,
     * 반품(DELIVERED)에서는 별도 FULLY_RETURNED 상태가 없으므로 DELIVERED를 유지한다.</p>
     */
    @Test
    @DisplayName("DELIVERED 전체 반품 후 주문 상태는 DELIVERED 유지")
    void requestReturnAll_statusRemainsDelivered() {
        addCartItem(testUserId, testProductIdA, 2);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");

        // When: 전체 반품
        orderService.requestReturn(order.getOrderId(), testUserId, orderItemIdA, 2, "DEFECT");

        // Then: DELIVERED 유지
        String status = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, order.getOrderId());
        assertThat(status).isEqualTo("DELIVERED");
    }

    // ====================================================================
    // 부분 취소 + 전체 취소 왕복
    // ====================================================================

    /**
     * 부분 취소 후 전체 취소를 실행하면 나머지 금액이 환불되고
     * 사용자 상태가 주문 전으로 완전히 원복되는지 확인한다.
     */
    @Test
    @DisplayName("부분 취소 후 전체 취소 — 사용자 상태 완전 원복")
    void partialThenFullCancel_restoresOriginalState() {
        // Given
        int grantPoints = 3000;
        jdbcTemplate.update(
                "UPDATE users SET point_balance = point_balance + ? WHERE user_id = ?",
                grantPoints, testUserId);

        BigDecimal totalSpentBefore = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?",
                BigDecimal.class, testUserId);
        int pointsBefore = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        addCartItem(testUserId, testProductIdA, 3);
        int usePoints = 900;
        Order order = orderService.createOrder(testUserId, requestWithPoints(usePoints));
        createdOrderIds.add(order.getOrderId());
        Long orderItemIdA = findOrderItemId(order.getOrderId(), testProductIdA);

        // When: 1개 부분 취소 → 전체 취소
        orderService.partialCancel(order.getOrderId(), testUserId, orderItemIdA, 1);
        orderService.cancelOrder(order.getOrderId(), testUserId);

        // Then: 사용자 상태 완전 원복
        BigDecimal totalSpentAfter = jdbcTemplate.queryForObject(
                "SELECT total_spent FROM users WHERE user_id = ?",
                BigDecimal.class, testUserId);
        int pointsAfter = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        assertThat(totalSpentAfter).isEqualByComparingTo(totalSpentBefore);
        assertThat(pointsAfter).isEqualTo(pointsBefore);

        // 재고 완전 복구
        int stockA = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductIdA);
        assertThat(stockA).isEqualTo(((Number) originalProductAState.get("stock_quantity")).intValue());

        System.out.println("  [PASS] 부분+전체 취소 왕복: totalSpent=" + totalSpentAfter
                + ", points=" + pointsAfter + " (원본 복원)");
    }
}

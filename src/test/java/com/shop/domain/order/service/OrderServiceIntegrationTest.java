package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.domain.order.entity.OrderStatus;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.InsufficientStockException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * OrderService 통합 테스트 — 주문 생성/취소 비즈니스 로직 검증
 *
 * 검증 항목:
 * 1) createOrder 정상 플로우: 장바구니 → 주문 → 재고 차감 → 장바구니 비우기 → 포인트 적립
 * 2) createOrder + 쿠폰 적용: 할인 금액 정확성, 쿠폰 사용 처리
 * 3) createOrder 예외: 빈 장바구니, 재고 부족
 * 4) cancelOrder 정상: 재고 복구 + 쿠폰 복원 + 포인트 회수
 * 5) cancelOrder 예외: 이미 취소된 주문, 배송완료 주문
 * 6) updateOrderStatus: 상태 전이
 *
 * 주의: 실제 PostgreSQL DB에 연결하여 테스트합니다.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 대상
    private Long testUserId;
    private Long testProductId;

    // 원본 상태 백업
    private Map<String, Object> originalProductState;
    private Map<String, Object> originalUserState;

    // 테스트 중 생성된 데이터 추적
    private final List<Long> createdOrderIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 1) 활성 상품 1개 (충분한 재고)
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        originalProductState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);

        // 2) 활성 사용자 1명 (빈 장바구니)
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

        System.out.println("  [setUp] 사용자 ID: " + testUserId + ", 상품 ID: " + testProductId);
    }

    @AfterEach
    void tearDown() {
        // 생성된 주문 관련 데이터 삭제
        for (Long orderId : createdOrderIds) {
            // user_coupons에서 order_id 참조 해제
            jdbcTemplate.update(
                    "UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL WHERE order_id = ?",
                    orderId);
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE reference_id = ?", orderId);
            // order_items는 CASCADE
            jdbcTemplate.update("DELETE FROM orders WHERE order_id = ?", orderId);
        }
        createdOrderIds.clear();

        // 재고 이력 정리 (주문 생성 시 reference_id가 null인 OUT 이력)
        jdbcTemplate.update(
                "DELETE FROM product_inventory_history WHERE product_id = ? AND created_by = ? AND reference_id IS NULL",
                testProductId, testUserId);

        // 장바구니 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);

        // 상품 원본 복원
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                originalProductState.get("stock_quantity"),
                originalProductState.get("sales_count"),
                testProductId);

        // 사용자 원본 복원
        jdbcTemplate.update(
                "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                originalUserState.get("total_spent"),
                originalUserState.get("point_balance"),
                originalUserState.get("tier_id"),
                testUserId);

        // 테스트용 쿠폰 정리
        jdbcTemplate.update(
                "DELETE FROM user_coupons WHERE coupon_id IN (SELECT coupon_id FROM coupons WHERE coupon_code IN ('TEST_ORDER_COUPON', 'TEST_CANCEL_COUPON'))");
        jdbcTemplate.update(
                "DELETE FROM coupons WHERE coupon_code IN ('TEST_ORDER_COUPON', 'TEST_CANCEL_COUPON')");
    }

    // ==================== 장바구니에 상품 추가 (공통 헬퍼) ====================

    private void addCartItem(Long userId, Long productId, int quantity) {
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                userId, productId, quantity, now, now);
    }

    private Long addCartItemAndReturnId(Long userId, Long productId, int quantity) {
        String now = LocalDateTime.now().toString();
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING cart_id
                """,
                Long.class,
                userId, productId, quantity, now, now);
    }

    private OrderCreateRequest defaultRequest() {
        return new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, null, null);
    }

    // ==================== createOrder 정상 플로우 ====================

    @Test
    @DisplayName("createOrder 성공 — 재고 차감 + 장바구니 비우기 + 포인트 적립")
    void createOrder_success() {
        // Given: 장바구니에 상품 2개 담기
        int quantity = 2;
        addCartItem(testUserId, testProductId, quantity);

        int stockBefore = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int pointsBefore = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        // When
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // Then: 주문 생성 확인
        assertThat(order.getOrderId()).isNotNull();
        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getUserId()).isEqualTo(testUserId);
        assertThat(order.getItems()).hasSize(1);
        assertThat(order.getItems().get(0).getQuantity()).isEqualTo(quantity);
        assertThat(order.getPointEarnRateSnapshot()).isNotNull();
        assertThat(order.getEarnedPointsSnapshot()).isGreaterThanOrEqualTo(0);

        // 재고 차감 확인
        int stockAfter = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stockAfter).isEqualTo(stockBefore - quantity);

        // 장바구니 비워졌는지 확인
        int cartCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(cartCount).isZero();

        // 포인트 적립 확인 (0보다 커야 함)
        int pointsAfter = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(pointsAfter).isGreaterThanOrEqualTo(pointsBefore);

        // 재고 이력 기록 확인
        int historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_inventory_history WHERE product_id = ? AND change_type = 'OUT' AND created_by = ?",
                Integer.class, testProductId, testUserId);
        assertThat(historyCount).isGreaterThanOrEqualTo(1);

        System.out.println("  [PASS] 주문 #" + order.getOrderNumber() + " 생성 완료, 재고: " + stockBefore + " → " + stockAfter);
    }

    @Test
    @DisplayName("createOrder 실패 — 빈 장바구니")
    void createOrder_emptyCart_throwsBusinessException() {
        // Given: 장바구니 비어있음 (setUp에서 이미 비어있음)

        // When & Then
        assertThatThrownBy(() -> orderService.createOrder(testUserId, defaultRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("장바구니가 비어있습니다");

        System.out.println("  [PASS] 빈 장바구니 주문 시 BusinessException 발생");
    }

    @Test
    @DisplayName("createOrder 실패 — 재고 부족")
    void createOrder_insufficientStock_throwsException() {
        // Given: 재고를 1로 설정하고, 장바구니에 5개 담기
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = 1 WHERE product_id = ?", testProductId);
        addCartItem(testUserId, testProductId, 5);

        // When & Then
        assertThatThrownBy(() -> orderService.createOrder(testUserId, defaultRequest()))
                .isInstanceOf(InsufficientStockException.class);

        // 재고 변경 없음 확인
        int stock = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        assertThat(stock).isEqualTo(1);

        System.out.println("  [PASS] 재고 부족 시 InsufficientStockException 발생, 재고 변경 없음");
    }

    @Test
    @DisplayName("createOrder 실패 — 선택 주문 요청 ID 일부 누락 시 실패")
    void createOrder_partialOrderWithMissingRequestedIds_throwsBusinessException() {
        // Given: 서로 다른 장바구니 항목 2개를 선택 주문 대상으로 구성
        Long secondProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 10 AND product_id <> ? LIMIT 1",
                Long.class, testProductId);

        Long firstCartId = addCartItemAndReturnId(testUserId, testProductId, 1);
        Long secondCartId = addCartItemAndReturnId(testUserId, secondProductId, 1);

        Long invalidCartId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(cart_id), 0) + 9999 FROM carts",
                Long.class);

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, null,
                List.of(firstCartId, secondCartId, secondCartId, invalidCartId));

        // When
        Throwable thrown = catchThrowable(() -> orderService.createOrder(testUserId, request));

        // Then
        assertThat(thrown).isInstanceOf(BusinessException.class);
        BusinessException businessException = (BusinessException) thrown;
        assertThat(businessException.getCode()).isEqualTo("INVALID_CART_SELECTION");
        assertThat(businessException).hasMessageContaining("유효하지 않거나 접근 불가한 장바구니 항목이 포함됨");

        Integer cartCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(cartCount).isEqualTo(2);
    }

    // ==================== createOrder + 쿠폰 ====================

    @Test
    @DisplayName("createOrder + 쿠폰 적용 — 할인 반영 + 쿠폰 사용 처리")
    void createOrder_withCoupon_appliesDiscount() {
        // Given: 장바구니 + 사용 가능한 쿠폰
        addCartItem(testUserId, testProductId, 1);

        // 사용자에게 발급된 미사용 쿠폰 찾기
        // 테스트용 쿠폰을 직접 생성하여 확실히 사용 가능한 상태 보장
        jdbcTemplate.update(
                """
                INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                    min_order_amount, max_discount, total_quantity, used_quantity,
                    valid_from, valid_until, is_active, created_at)
                VALUES ('TEST_ORDER_COUPON', '주문테스트쿠폰', 'FIXED', 1000,
                    0, NULL, 100, 0,
                    '2024-01-01'::timestamp, '2027-12-31'::timestamp, true, NOW())
                ON CONFLICT (coupon_code) DO NOTHING
                """);

        Integer testCouponId = jdbcTemplate.queryForObject(
                "SELECT coupon_id FROM coupons WHERE coupon_code = 'TEST_ORDER_COUPON'",
                Integer.class);

        // 사용자에게 쿠폰 발급
        jdbcTemplate.update(
                """
                INSERT INTO user_coupons (user_id, coupon_id, is_used, issued_at, expires_at)
                VALUES (?, ?, false, NOW(), '2027-12-31'::timestamp)
                ON CONFLICT DO NOTHING
                """,
                testUserId, testCouponId);

        Long userCouponId = jdbcTemplate.queryForObject(
                "SELECT user_coupon_id FROM user_coupons WHERE user_id = ? AND coupon_id = ? AND is_used = false",
                Long.class, testUserId, testCouponId);

        if (userCouponId == null) {
            System.out.println("  [SKIP] 테스트 쿠폰 발급 실패, 건너뜁니다.");
            return;
        }

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, userCouponId, null, null);

        // When
        Order order = orderService.createOrder(testUserId, request);
        createdOrderIds.add(order.getOrderId());

        // Then: 할인 적용 확인
        assertThat(order.getDiscountAmount()).isGreaterThan(BigDecimal.ZERO);

        // finalAmount = totalAmount - discountAmount + shippingFee 이므로
        // 할인이 적용되었다면: finalAmount < totalAmount + shippingFee
        BigDecimal withoutDiscount = order.getTotalAmount().add(order.getShippingFee());
        assertThat(order.getFinalAmount()).isLessThan(withoutDiscount);

        // 쿠폰 사용 처리 확인
        Boolean isUsed = jdbcTemplate.queryForObject(
                "SELECT is_used FROM user_coupons WHERE user_coupon_id = ?",
                Boolean.class, userCouponId);
        assertThat(isUsed).isTrue();

        Long couponOrderId = jdbcTemplate.queryForObject(
                "SELECT order_id FROM user_coupons WHERE user_coupon_id = ?",
                Long.class, userCouponId);
        assertThat(couponOrderId).isEqualTo(order.getOrderId());

        System.out.println("  [PASS] 쿠폰 적용 주문: 총액=" + order.getTotalAmount()
                + ", 할인=" + order.getDiscountAmount() + ", 최종=" + order.getFinalAmount());
    }

    @Test
    @DisplayName("createOrder — 클라이언트 배송비 전달값과 무관하게 서버 계산 배송비 저장")
    void createOrder_ignoresClientShippingFeeAndStoresServerCalculatedFee() {
        // Given
        int quantity = 1;
        addCartItem(testUserId, testProductId, quantity);

        BigDecimal productPrice = jdbcTemplate.queryForObject(
                "SELECT price FROM products WHERE product_id = ?",
                BigDecimal.class, testProductId);
        BigDecimal expectedTotalAmount = productPrice.multiply(BigDecimal.valueOf(quantity));

        BigDecimal freeShippingThreshold = jdbcTemplate.queryForObject(
                """
                SELECT t.free_shipping_threshold
                FROM users u
                JOIN user_tiers t ON t.tier_id = u.tier_id
                WHERE u.user_id = ?
                """,
                BigDecimal.class, testUserId);
        BigDecimal expectedShippingFee = expectedTotalAmount.compareTo(freeShippingThreshold) >= 0
                ? BigDecimal.ZERO
                : new BigDecimal("3000");

        OrderCreateRequest tamperedRequest = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", new BigDecimal("999999"), null, null, null);

        // When
        Order order = orderService.createOrder(testUserId, tamperedRequest);
        createdOrderIds.add(order.getOrderId());

        // Then
        assertThat(order.getShippingFee()).isEqualByComparingTo(expectedShippingFee);

        BigDecimal persistedShippingFee = jdbcTemplate.queryForObject(
                "SELECT shipping_fee FROM orders WHERE order_id = ?",
                BigDecimal.class, order.getOrderId());
        assertThat(persistedShippingFee).isEqualByComparingTo(expectedShippingFee);
        assertThat(persistedShippingFee).isNotEqualByComparingTo("999999");
    }

    // ==================== cancelOrder ====================

    @Test
    @DisplayName("cancelOrder 성공 — 주문/취소 왕복 시 포인트 정확히 원복")
    void cancelOrder_success_restoresStockAndPoints() {
        // Given: 주문 전 기준 상태 저장
        addCartItem(testUserId, testProductId, 3);

        int stockBeforeOrder = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int salesBeforeOrder = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int pointsBeforeOrder = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        // When: 주문 생성
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        int stockAfterOrder = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int salesAfterOrder = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int pointsAfterOrder = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        // 스냅샷 컬럼 저장 검증
        Map<String, Object> pointSnapshot = jdbcTemplate.queryForMap(
                "SELECT point_earn_rate_snapshot, earned_points_snapshot FROM orders WHERE order_id = ?",
                order.getOrderId());
        BigDecimal pointEarnRateSnapshot = (BigDecimal) pointSnapshot.get("point_earn_rate_snapshot");
        Integer earnedPointsSnapshot = (Integer) pointSnapshot.get("earned_points_snapshot");

        assertThat(pointEarnRateSnapshot).isEqualByComparingTo(order.getPointEarnRateSnapshot());
        assertThat(earnedPointsSnapshot).isEqualTo(order.getEarnedPointsSnapshot());
        assertThat(pointsAfterOrder - pointsBeforeOrder).isEqualTo(order.getEarnedPointsSnapshot());

        // When: 주문 취소
        orderService.cancelOrder(order.getOrderId(), testUserId);

        // Then: 재고/포인트 모두 주문 전 상태로 정확히 원복
        int stockAfterCancel = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int salesAfterCancel = jdbcTemplate.queryForObject(
                "SELECT sales_count FROM products WHERE product_id = ?",
                Integer.class, testProductId);
        int pointsAfterCancel = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        assertThat(stockAfterOrder).isEqualTo(stockBeforeOrder - 3);
        assertThat(stockAfterCancel).isEqualTo(stockBeforeOrder);
        assertThat(salesAfterOrder).isEqualTo(salesBeforeOrder + 3);
        assertThat(salesAfterCancel).isEqualTo(salesBeforeOrder);
        assertThat(pointsAfterCancel).isEqualTo(pointsBeforeOrder);

        // 주문 상태 확인
        String status = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, order.getOrderId());
        assertThat(status).isEqualTo("CANCELLED");

        // 취소 재고 이력 확인
        int returnHistory = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_inventory_history WHERE reference_id = ? AND reason = 'RETURN'",
                Integer.class, order.getOrderId());
        assertThat(returnHistory).isGreaterThanOrEqualTo(1);

        System.out.println("  [PASS] 주문-취소 왕복: 재고 " + stockBeforeOrder + " → " + stockAfterOrder + " → " + stockAfterCancel
                + ", 판매량 " + salesBeforeOrder + " → " + salesAfterOrder + " → " + salesAfterCancel
                + ", 포인트 " + pointsBeforeOrder + " → " + pointsAfterOrder + " → " + pointsAfterCancel
                + ", 적립 스냅샷=" + earnedPointsSnapshot + "P");
    }

    @Test
    @DisplayName("cancelOrder 실패 — 이미 취소된 주문")
    void cancelOrder_alreadyCancelled_throwsException() {
        // Given: 주문 생성 후 취소
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());
        orderService.cancelOrder(order.getOrderId(), testUserId);

        // When & Then: 다시 취소 시도
        assertThatThrownBy(() -> orderService.cancelOrder(order.getOrderId(), testUserId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소할 수 없는");

        System.out.println("  [PASS] 이미 취소된 주문 재취소 시 BusinessException 발생");
    }

    @Test
    @DisplayName("cancelOrder 실패 — 배송완료 주문은 취소 불가")
    void cancelOrder_deliveredOrder_throwsException() {
        // Given: 주문 생성 후 배송완료 상태로 변경
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // 상태를 DELIVERED로 직접 변경
        jdbcTemplate.update(
                "UPDATE orders SET order_status = 'DELIVERED', delivered_at = NOW() WHERE order_id = ?",
                order.getOrderId());

        // When & Then
        assertThatThrownBy(() -> orderService.cancelOrder(order.getOrderId(), testUserId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("취소할 수 없는");

        System.out.println("  [PASS] 배송완료 주문 취소 시 BusinessException 발생");
    }



    // ==================== 조회 ====================

    @Test
    @DisplayName("getOrdersByUser — 사용자 주문 페이징 조회")
    void getOrdersByUser_returnsUserOrders() {
        // Given
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // When
        Page<Order> page = orderService.getOrdersByUser(testUserId, PageRequest.of(0, 20));

        // Then
        assertThat(page.getContent())
                .as("사용자 주문 목록에 생성한 주문이 포함되어야 함")
                .anyMatch(o -> o.getOrderId().equals(order.getOrderId()));

        System.out.println("  [PASS] 사용자 주문 조회: total=" + page.getTotalElements());
    }

    @Test
    @DisplayName("getAllOrders — 전체 주문 페이징 조회")
    void getAllOrders_returnsAllOrders() {
        // Given
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // When
        Page<Order> page = orderService.getAllOrders(PageRequest.of(0, 50));

        // Then
        assertThat(page.getContent())
                .as("전체 주문 조회에 생성한 주문이 포함되어야 함")
                .anyMatch(o -> o.getOrderId().equals(order.getOrderId()));

        System.out.println("  [PASS] 전체 주문 조회: total=" + page.getTotalElements());
    }

    @Test
    @DisplayName("getOrdersByStatus — 상태별 주문 페이징 조회")
    void getOrdersByStatus_returnsFilteredOrders() {
        // Given: createOrder 직후 상태는 PAID
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // When
        Page<Order> paidPage = orderService.getOrdersByStatus("PAID", PageRequest.of(0, 50));

        // Then
        assertThat(paidPage.getContent())
                .as("PAID 상태 조회에 생성한 주문이 포함되어야 함")
                .anyMatch(o -> o.getOrderId().equals(order.getOrderId()));
        assertThat(paidPage.getContent())
                .as("상태별 조회 결과는 요청한 상태와 일치해야 함")
                .allMatch(o -> o.getOrderStatus() == OrderStatus.PAID);

        System.out.println("  [PASS] 상태별 주문 조회(PAID): total=" + paidPage.getTotalElements());
    }

    // ==================== updateOrderStatus ====================

    @Test
    @DisplayName("updateOrderStatus — PAID → SHIPPED → DELIVERED 정상 전이")
    void updateOrderStatus_validTransitions() {
        // Given
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // When & Then: SHIPPED
        orderService.updateOrderStatus(order.getOrderId(), "SHIPPED");
        String status1 = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, order.getOrderId());
        assertThat(status1).isEqualTo("SHIPPED");

        // DELIVERED
        orderService.updateOrderStatus(order.getOrderId(), "DELIVERED");
        String status2 = jdbcTemplate.queryForObject(
                "SELECT order_status FROM orders WHERE order_id = ?",
                String.class, order.getOrderId());
        assertThat(status2).isEqualTo("DELIVERED");

        System.out.println("  [PASS] 상태 전이: PAID → SHIPPED → DELIVERED");
    }

    @Test
    @DisplayName("updateOrderStatus — 잘못된 상태 코드")
    void updateOrderStatus_invalidStatus_throwsException() {
        // Given
        addCartItem(testUserId, testProductId, 1);
        Order order = orderService.createOrder(testUserId, defaultRequest());
        createdOrderIds.add(order.getOrderId());

        // When & Then
        assertThatThrownBy(() -> orderService.updateOrderStatus(order.getOrderId(), "INVALID"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("잘못된 주문 상태");

        System.out.println("  [PASS] 잘못된 상태 코드 시 BusinessException 발생");
    }

    // ==================== cancelOrder + 쿠폰 복원 ====================

    @Test
    @DisplayName("cancelOrder + 쿠폰 복원 — 취소 시 쿠폰이 미사용 상태로 돌아감")
    void cancelOrder_restoresCoupon() {
        // Given: 쿠폰이 있는 주문 생성
        addCartItem(testUserId, testProductId, 1);

        // 테스트용 쿠폰을 직접 생성하여 확실히 사용 가능한 상태 보장
        jdbcTemplate.update(
                """
                INSERT INTO coupons (coupon_code, coupon_name, discount_type, discount_value,
                    min_order_amount, max_discount, total_quantity, used_quantity,
                    valid_from, valid_until, is_active, created_at)
                VALUES ('TEST_CANCEL_COUPON', '취소테스트쿠폰', 'FIXED', 1000,
                    0, NULL, 100, 0,
                    '2024-01-01'::timestamp, '2027-12-31'::timestamp, true, NOW())
                ON CONFLICT (coupon_code) DO NOTHING
                """);

        Integer testCouponId = jdbcTemplate.queryForObject(
                "SELECT coupon_id FROM coupons WHERE coupon_code = 'TEST_CANCEL_COUPON'",
                Integer.class);

        jdbcTemplate.update(
                """
                INSERT INTO user_coupons (user_id, coupon_id, is_used, issued_at, expires_at)
                VALUES (?, ?, false, NOW(), '2027-12-31'::timestamp)
                ON CONFLICT DO NOTHING
                """,
                testUserId, testCouponId);

        Long userCouponId = jdbcTemplate.queryForObject(
                "SELECT user_coupon_id FROM user_coupons WHERE user_id = ? AND coupon_id = ? AND is_used = false",
                Long.class, testUserId, testCouponId);

        if (userCouponId == null) {
            System.out.println("  [SKIP] 테스트 쿠폰 발급 실패, 건너뜁니다.");
            return;
        }

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, userCouponId, null, null);

        Order order = orderService.createOrder(testUserId, request);
        createdOrderIds.add(order.getOrderId());

        // 쿠폰 사용됨 확인
        Boolean usedAfterOrder = jdbcTemplate.queryForObject(
                "SELECT is_used FROM user_coupons WHERE user_coupon_id = ?",
                Boolean.class, userCouponId);
        assertThat(usedAfterOrder).isTrue();

        // When: 취소
        orderService.cancelOrder(order.getOrderId(), testUserId);

        // Then: 쿠폰 복원 확인
        Boolean usedAfterCancel = jdbcTemplate.queryForObject(
                "SELECT is_used FROM user_coupons WHERE user_coupon_id = ?",
                Boolean.class, userCouponId);
        assertThat(usedAfterCancel).isFalse();

        Long orderIdOnCoupon = jdbcTemplate.queryForObject(
                "SELECT order_id FROM user_coupons WHERE user_coupon_id = ?",
                Long.class, userCouponId);
        assertThat(orderIdOnCoupon).isNull();

        System.out.println("  [PASS] 주문 취소 시 쿠폰 복원 완료");
    }

    // ==================== 포인트 사용 ====================

    @Test
    @DisplayName("createOrder + 포인트 사용 — 포인트 차감 + 최종금액 반영 + 취소 시 환불")
    void createOrder_withPoints_deductsAndRefundsOnCancel() {
        // Given: 사용자에게 포인트 부여
        int grantPoints = 5000;
        jdbcTemplate.update(
                "UPDATE users SET point_balance = point_balance + ? WHERE user_id = ?",
                grantPoints, testUserId);

        int pointsBefore = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        addCartItem(testUserId, testProductId, 1);

        int usePoints = 2000;
        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, usePoints, null);

        // When: 주문 생성
        Order order = orderService.createOrder(testUserId, request);
        createdOrderIds.add(order.getOrderId());

        // Then: 포인트 차감 확인
        int pointsAfterOrder = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);

        // 차감된 포인트 = usePoints - 적립 포인트 (순 차감)
        assertThat(pointsAfterOrder).isEqualTo(pointsBefore - usePoints + order.getEarnedPointsSnapshot());

        // DB에 used_points 저장 확인
        Integer storedUsedPoints = jdbcTemplate.queryForObject(
                "SELECT used_points FROM orders WHERE order_id = ?",
                Integer.class, order.getOrderId());
        assertThat(storedUsedPoints).isEqualTo(usePoints);

        // 최종금액에 포인트 할인 반영 확인
        // finalAmount = totalAmount - discountAmount - usedPoints + shippingFee
        BigDecimal expectedFinal = order.getTotalAmount()
                .subtract(order.getDiscountAmount())
                .subtract(BigDecimal.valueOf(usePoints))
                .add(order.getShippingFee());
        if (expectedFinal.compareTo(BigDecimal.ZERO) < 0) expectedFinal = BigDecimal.ZERO;
        assertThat(order.getFinalAmount()).isEqualByComparingTo(expectedFinal);

        // When: 취소
        orderService.cancelOrder(order.getOrderId(), testUserId);

        // Then: 포인트 완전 원복 (적립 취소 + 사용분 환불)
        int pointsAfterCancel = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(pointsAfterCancel).isEqualTo(pointsBefore);

        System.out.println("  [PASS] 포인트 사용 주문-취소 왕복: 포인트 " + pointsBefore
                + " → " + pointsAfterOrder + " (사용 " + usePoints + ", 적립 " + order.getEarnedPointsSnapshot() + ")"
                + " → " + pointsAfterCancel + " (환불)");
    }

    @Test
    @DisplayName("createOrder + 포인트 초과 사용 — 보유량 초과 시 예외 발생")
    void createOrder_withExcessivePoints_throwsException() {
        // Given: 포인트 잔액을 100으로 설정
        jdbcTemplate.update(
                "UPDATE users SET point_balance = 100 WHERE user_id = ?", testUserId);

        addCartItem(testUserId, testProductId, 1);

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 강남구 테스트로 123", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, 99999, null);

        // When & Then
        assertThatThrownBy(() -> orderService.createOrder(testUserId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("포인트가 부족합니다");

        // 포인트 변화 없음 확인
        int pointsAfter = jdbcTemplate.queryForObject(
                "SELECT point_balance FROM users WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(pointsAfter).isEqualTo(100);

        System.out.println("  [PASS] 포인트 초과 사용 시 BusinessException 발생, 포인트 변화 없음");
    }
}

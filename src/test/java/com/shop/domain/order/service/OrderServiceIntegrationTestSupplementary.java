package com.shop.domain.order.service;

import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.domain.order.entity.Order;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * OrderService 추가 통합 테스트 — getOrderDetail
 *
 * [FIX] 기존 코드는 setUp 없이 "SELECT ... FROM orders LIMIT 1"로 기존 주문을 조회했지만,
 * test-reset.sql이 스키마를 완전히 초기화(DROP SCHEMA CASCADE)하므로
 * orders 테이블이 비어 있어 EmptyResultDataAccessException이 발생했다.
 *
 * 수정: @BeforeEach에서 테스트용 주문을 직접 생성하고,
 * @AfterEach에서 생성 데이터를 정리하여 테스트 격리를 보장한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class OrderServiceIntegrationTestSupplementary {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // setUp에서 생성한 테스트 주문 정보
    private Long testOrderId;
    private Long testUserId;

    // 원본 상태 백업 (tearDown 복원용)
    private Map<String, Object> originalProductState;
    private Map<String, Object> originalUserState;
    private Long testProductId;

    @BeforeEach
    void setUp() {
        // 1) 재고 충분한 활성 상품 선택
        testProductId = jdbcTemplate.queryForObject(
                "SELECT product_id FROM products WHERE is_active = true AND stock_quantity >= 100 LIMIT 1",
                Long.class);

        originalProductState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity, sales_count FROM products WHERE product_id = ?",
                testProductId);

        // 2) 빈 장바구니를 가진 활성 사용자 선택
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

        // 3) 장바구니에 상품 추가 후 주문 생성
        String now = LocalDateTime.now().toString();
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);
        jdbcTemplate.update(
                "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                testUserId, testProductId, now, now);

        OrderCreateRequest request = new OrderCreateRequest(
                "서울시 테스트구 보충테스트로 1", "테스트수령인", "010-0000-0000",
                "CARD", BigDecimal.ZERO, null, null, null);

        Order order = orderService.createOrder(testUserId, request);
        testOrderId = order.getOrderId();

        System.out.println("  [setUp] 사용자: " + testUserId + ", 주문: " + testOrderId);
    }

    @AfterEach
    void tearDown() {
        // 생성된 주문 관련 데이터 정리
        if (testOrderId != null) {
            jdbcTemplate.update(
                    "DELETE FROM point_history WHERE reference_id = ? AND reference_type IN ('ORDER', 'CANCEL')",
                    testOrderId);
            jdbcTemplate.update(
                    "UPDATE user_coupons SET is_used = false, used_at = NULL, order_id = NULL WHERE order_id = ?",
                    testOrderId);
            jdbcTemplate.update(
                    "DELETE FROM product_inventory_history WHERE reference_id = ?",
                    testOrderId);
            jdbcTemplate.update("DELETE FROM orders WHERE order_id = ?", testOrderId);
        }

        // 장바구니 정리
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);

        // 상품 원복
        if (originalProductState != null) {
            jdbcTemplate.update(
                    "UPDATE products SET stock_quantity = ?, sales_count = ? WHERE product_id = ?",
                    originalProductState.get("stock_quantity"),
                    originalProductState.get("sales_count"),
                    testProductId);
        }

        // 사용자 원복
        if (originalUserState != null) {
            jdbcTemplate.update(
                    "UPDATE users SET total_spent = ?, point_balance = ?, tier_id = ? WHERE user_id = ?",
                    originalUserState.get("total_spent"),
                    originalUserState.get("point_balance"),
                    originalUserState.get("tier_id"),
                    testUserId);
        }
    }

    @Test
    @DisplayName("getOrderDetail — 주문 소유자가 조회하면 성공")
    void getOrderDetail_ownerAccess_success() {
        // When: setUp에서 생성한 주문을 소유자가 조회
        Order order = orderService.getOrderDetail(testOrderId, testUserId);

        // Then
        assertThat(order).isNotNull();
        assertThat(order.getOrderId()).isEqualTo(testOrderId);
        assertThat(order.getUserId()).isEqualTo(testUserId);

        System.out.println("  [PASS] getOrderDetail: 주문 #" + order.getOrderNumber());
    }

    @Test
    @DisplayName("getOrderDetail — 다른 사용자가 조회하면 ResourceNotFoundException")
    void getOrderDetail_nonOwner_throwsException() {
        // When & Then: 다른 사용자 ID로 조회 시도
        assertThatThrownBy(() -> orderService.getOrderDetail(testOrderId, testUserId + 99999))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] getOrderDetail 타인 조회 → ResourceNotFoundException");
    }

    @Test
    @DisplayName("getOrderDetail — 존재하지 않는 주문 ID")
    void getOrderDetail_nonExistent_throwsException() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(order_id), 0) FROM orders", Long.class);

        assertThatThrownBy(() -> orderService.getOrderDetail(maxId + 9999, 1L))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 주문 → ResourceNotFoundException");
    }
}

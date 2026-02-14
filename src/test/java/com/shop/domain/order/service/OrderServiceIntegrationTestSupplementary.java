package com.shop.domain.order.service;

import com.shop.domain.order.entity.Order;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * OrderService 추가 통합 테스트 — getOrderDetail
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

    @Test
    @DisplayName("getOrderDetail — 주문 소유자가 조회하면 성공")
    void getOrderDetail_ownerAccess_success() {
        // 기존 주문 조회
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT order_id, user_id FROM orders LIMIT 1");
        Long orderId = ((Number) row.get("order_id")).longValue();
        Long userId = ((Number) row.get("user_id")).longValue();

        Order order = orderService.getOrderDetail(orderId, userId);

        assertThat(order).isNotNull();
        assertThat(order.getOrderId()).isEqualTo(orderId);
        assertThat(order.getUserId()).isEqualTo(userId);

        System.out.println("  [PASS] getOrderDetail: 주문 #" + order.getOrderNumber());
    }

    @Test
    @DisplayName("getOrderDetail — 다른 사용자가 조회하면 ResourceNotFoundException")
    void getOrderDetail_nonOwner_throwsException() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT order_id, user_id FROM orders LIMIT 1");
        Long orderId = ((Number) row.get("order_id")).longValue();
        Long userId = ((Number) row.get("user_id")).longValue();

        // user_id + 99999로 다른 사용자 시뮬레이션
        assertThatThrownBy(() -> orderService.getOrderDetail(orderId, userId + 99999))
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

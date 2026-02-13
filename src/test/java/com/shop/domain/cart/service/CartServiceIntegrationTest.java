package com.shop.domain.cart.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * CartService 통합 테스트 — 장바구니 CRUD 및 엣지 케이스 검증
 *
 * 검증 항목:
 * 1) addToCart 정상: 새 상품 추가, 기존 상품 수량 누적
 * 2) addToCart 예외: 비활성 상품, 재고 초과, 수량 0 이하, 최대 50개 제한
 * 3) updateQuantity: 수량 변경, 수량 0 → 삭제
 * 4) removeFromCart / clearCart
 * 5) calculateTotal: 금액 계산
 *
 * 주의: 실제 PostgreSQL DB에 연결하여 테스트합니다.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class CartServiceIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long activeProductId;
    private Long activeProductId2;
    private BigDecimal productPrice;
    private Map<String, Object> originalProductState;

    @BeforeEach
    void setUp() {
        // 빈 장바구니를 가진 활성 사용자
        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM carts c WHERE c.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);

        // 활성 상품 2개 (충분한 재고)
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
                "SELECT product_id, price FROM products WHERE is_active = true AND stock_quantity >= 50 ORDER BY product_id LIMIT 2");

        if (products.size() < 2) {
            throw new RuntimeException("테스트 가능한 상품이 2개 이상 필요합니다.");
        }

        activeProductId = ((Number) products.get(0).get("product_id")).longValue();
        activeProductId2 = ((Number) products.get(1).get("product_id")).longValue();
        productPrice = (BigDecimal) products.get(0).get("price");

        originalProductState = jdbcTemplate.queryForMap(
                "SELECT stock_quantity FROM products WHERE product_id = ?", activeProductId);

        System.out.println("  [setUp] 사용자: " + testUserId
                + ", 상품1: " + activeProductId + ", 상품2: " + activeProductId2);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM carts WHERE user_id = ?", testUserId);
        jdbcTemplate.update("UPDATE products SET stock_quantity = ? WHERE product_id = ?",
                originalProductState.get("stock_quantity"), activeProductId);
    }

    // ==================== addToCart 정상 ====================

    @Test
    @DisplayName("addToCart — 새 상품 추가")
    void addToCart_newItem() {
        // When
        cartService.addToCart(testUserId, activeProductId, 2);

        // Then
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(count).isEqualTo(1);

        int quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(quantity).isEqualTo(2);

        System.out.println("  [PASS] 새 상품 추가: 수량=2");
    }

    @Test
    @DisplayName("addToCart — 기존 상품에 수량 누적")
    void addToCart_existingItem_accumulatesQuantity() {
        // Given: 이미 장바구니에 3개
        cartService.addToCart(testUserId, activeProductId, 3);

        // When: 추가로 2개
        cartService.addToCart(testUserId, activeProductId, 2);

        // Then: 총 5개
        int quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(quantity).isEqualTo(5);

        // 레코드는 1개만 (중복 INSERT 아님)
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(count).isEqualTo(1);

        System.out.println("  [PASS] 수량 누적: 3 + 2 = 5");
    }

    @Test
    @DisplayName("addToCart — 서로 다른 상품 추가")
    void addToCart_differentProducts() {
        // When
        cartService.addToCart(testUserId, activeProductId, 1);
        cartService.addToCart(testUserId, activeProductId2, 3);

        // Then
        int totalItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(totalItems).isEqualTo(2);

        System.out.println("  [PASS] 서로 다른 상품 2개 추가");
    }

    // ==================== addToCart 예외 ====================

    @Test
    @DisplayName("addToCart 실패 — 수량 0 이하")
    void addToCart_zeroQuantity_throwsException() {
        assertThatThrownBy(() -> cartService.addToCart(testUserId, activeProductId, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1개 이상");

        assertThatThrownBy(() -> cartService.addToCart(testUserId, activeProductId, -1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1개 이상");

        System.out.println("  [PASS] 수량 0 이하 → BusinessException");
    }

    @Test
    @DisplayName("addToCart 실패 — 비활성(판매 중지) 상품")
    void addToCart_inactiveProduct_throwsException() {
        // 비활성 상품 찾기
        List<Long> inactiveProducts = jdbcTemplate.queryForList(
                "SELECT product_id FROM products WHERE is_active = false LIMIT 1",
                Long.class);

        if (inactiveProducts.isEmpty()) {
            System.out.println("  [SKIP] 비활성 상품이 없어 테스트를 건너뜁니다.");
            return;
        }

        Long inactiveProductId = inactiveProducts.get(0);

        assertThatThrownBy(() -> cartService.addToCart(testUserId, inactiveProductId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("판매 중지");

        System.out.println("  [PASS] 비활성 상품 → BusinessException");
    }

    @Test
    @DisplayName("addToCart 실패 — 재고 초과")
    void addToCart_exceedsStock_throwsException() {
        // Given: 재고를 3으로 설정
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = 3 WHERE product_id = ?", activeProductId);

        // When & Then: 5개 추가 시도
        assertThatThrownBy(() -> cartService.addToCart(testUserId, activeProductId, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("재고");

        System.out.println("  [PASS] 재고 초과 → BusinessException");
    }

    @Test
    @DisplayName("addToCart 실패 — 누적 수량이 재고 초과")
    void addToCart_accumulatedQuantityExceedsStock_throwsException() {
        // Given: 재고 5, 장바구니에 이미 3개
        jdbcTemplate.update(
                "UPDATE products SET stock_quantity = 5 WHERE product_id = ?", activeProductId);
        cartService.addToCart(testUserId, activeProductId, 3);

        // When & Then: 추가 3개 (합계 6 > 재고 5)
        assertThatThrownBy(() -> cartService.addToCart(testUserId, activeProductId, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("재고");

        // 기존 수량 유지 확인
        int quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(quantity).isEqualTo(3);

        System.out.println("  [PASS] 누적 수량 재고 초과 → BusinessException, 기존 수량 유지");
    }

    @Test
    @DisplayName("addToCart 실패 — 최대 50개 제한")
    void addToCart_maxCartItems_throwsException() {
        // Given: 50개 상품을 직접 INSERT
        List<Long> productIds = jdbcTemplate.queryForList(
                "SELECT product_id FROM products WHERE is_active = true ORDER BY product_id LIMIT 50",
                Long.class);

        if (productIds.size() < 50) {
            System.out.println("  [SKIP] 활성 상품이 50개 미만이어 테스트를 건너뜁니다.");
            return;
        }

        String now = java.time.LocalDateTime.now().toString();
        for (Long pid : productIds) {
            jdbcTemplate.update(
                    "INSERT INTO carts (user_id, product_id, quantity, added_at, updated_at) VALUES (?, ?, 1, ?, ?) ON CONFLICT DO NOTHING",
                    testUserId, pid, now, now);
        }

        int currentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?",
                Integer.class, testUserId);

        if (currentCount < 50) {
            System.out.println("  [SKIP] 장바구니를 50개로 채우지 못했습니다. (현재: " + currentCount + ")");
            return;
        }

        // 50개에 포함되지 않은 새 상품 찾기
        Long newProductId = jdbcTemplate.queryForObject(
                """
                SELECT p.product_id FROM products p
                WHERE p.is_active = true AND p.stock_quantity > 0
                  AND p.product_id NOT IN (SELECT c.product_id FROM carts c WHERE c.user_id = ?)
                LIMIT 1
                """,
                Long.class, testUserId);

        // When & Then: 51번째 상품 추가 시도
        assertThatThrownBy(() -> cartService.addToCart(testUserId, newProductId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("최대");

        System.out.println("  [PASS] 50개 제한 초과 → BusinessException");
    }

    @Test
    @DisplayName("addToCart 실패 — 존재하지 않는 상품")
    void addToCart_nonExistentProduct_throwsException() {
        assertThatThrownBy(() -> cartService.addToCart(testUserId, 999999L, 1))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 상품 → ResourceNotFoundException");
    }

    // ==================== updateQuantity ====================

    @Test
    @DisplayName("updateQuantity — 수량 변경")
    void updateQuantity_changesQuantity() {
        // Given
        cartService.addToCart(testUserId, activeProductId, 2);

        // When
        cartService.updateQuantity(testUserId, activeProductId, 5);

        // Then
        int quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(quantity).isEqualTo(5);

        System.out.println("  [PASS] 수량 변경: 2 → 5");
    }

    @Test
    @DisplayName("updateQuantity — 수량 0으로 변경 시 삭제")
    void updateQuantity_zeroRemovesItem() {
        // Given
        cartService.addToCart(testUserId, activeProductId, 3);

        // When
        cartService.updateQuantity(testUserId, activeProductId, 0);

        // Then
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId);
        assertThat(count).isZero();

        System.out.println("  [PASS] 수량 0 → 장바구니에서 삭제됨");
    }

    @Test
    @DisplayName("updateQuantity — 존재하지 않는 장바구니 항목")
    void updateQuantity_nonExistent_throwsException() {
        assertThatThrownBy(() -> cartService.updateQuantity(testUserId, 999999L, 5))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 항목 수량 변경 → ResourceNotFoundException");
    }

    // ==================== removeFromCart / clearCart ====================

    @Test
    @DisplayName("removeFromCart — 특정 상품 제거")
    void removeFromCart_removesSpecificItem() {
        // Given: 2개 상품 추가
        cartService.addToCart(testUserId, activeProductId, 1);
        cartService.addToCart(testUserId, activeProductId2, 1);

        // When: 하나만 제거
        cartService.removeFromCart(testUserId, activeProductId);

        // Then: 하나만 남음
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(count).isEqualTo(1);

        int remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, activeProductId2);
        assertThat(remaining).isEqualTo(1);

        System.out.println("  [PASS] 특정 상품 제거 후 1개 남음");
    }

    @Test
    @DisplayName("clearCart — 장바구니 전체 비우기")
    void clearCart_removesAllItems() {
        // Given: 2개 상품 추가
        cartService.addToCart(testUserId, activeProductId, 2);
        cartService.addToCart(testUserId, activeProductId2, 3);

        // When
        cartService.clearCart(testUserId);

        // Then
        int count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?",
                Integer.class, testUserId);
        assertThat(count).isZero();

        System.out.println("  [PASS] 장바구니 전체 비우기 완료");
    }

    // ==================== getCartItems / getCartCount / calculateTotal ====================

    @Test
    @DisplayName("getCartItems + getCartCount + calculateTotal — 조회 및 금액 계산")
    void getCartItems_andCalculateTotal() {
        // Given
        cartService.addToCart(testUserId, activeProductId, 2);
        cartService.addToCart(testUserId, activeProductId2, 1);

        // When
        List<Cart> items = cartService.getCartItems(testUserId);
        int count = cartService.getCartCount(testUserId);
        BigDecimal total = cartService.calculateTotal(items);

        // Then
        assertThat(items).hasSize(2);
        assertThat(count).isEqualTo(2);
        assertThat(total).isGreaterThan(BigDecimal.ZERO);

        // 금액 검증: 각 상품의 price * quantity 합산
        BigDecimal expectedTotal = items.stream()
                .map(c -> c.getProduct().getPrice().multiply(BigDecimal.valueOf(c.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo(expectedTotal);

        System.out.println("  [PASS] 장바구니 조회: " + items.size() + "개, 총액: " + total);
    }
}

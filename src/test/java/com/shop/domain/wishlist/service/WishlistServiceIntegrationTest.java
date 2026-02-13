package com.shop.domain.wishlist.service;

import com.shop.domain.wishlist.entity.Wishlist;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * WishlistService 통합 테스트 — 위시리스트 토글/조회
 *
 * 검증 항목:
 * 1) toggleWishlist: 추가 → 제거 → 추가 (토글 동작)
 * 2) toggleWishlist 예외: 존재하지 않는 상품
 * 3) isWishlisted: 상태 반영 확인
 * 4) getWishlist: 페이징 조회, 상품 정보 포함
 * 5) 여러 상품 위시리스트에 추가 후 제거
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN"
})
class WishlistServiceIntegrationTest {

    @Autowired
    private WishlistService wishlistService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long testUserId;
    private Long productId1;
    private Long productId2;

    @BeforeEach
    void setUp() {
        // 위시리스트가 비어있는 사용자
        testUserId = jdbcTemplate.queryForObject(
                """
                SELECT u.user_id FROM users u
                WHERE u.is_active = true AND u.role = 'ROLE_USER'
                  AND NOT EXISTS (SELECT 1 FROM wishlists w WHERE w.user_id = u.user_id)
                ORDER BY u.user_id LIMIT 1
                """,
                Long.class);

        // 활성 상품 2개
        List<Long> products = jdbcTemplate.queryForList(
                "SELECT product_id FROM products WHERE is_active = true ORDER BY product_id LIMIT 2",
                Long.class);
        productId1 = products.get(0);
        productId2 = products.get(1);

        System.out.println("  [setUp] 사용자: " + testUserId
                + ", 상품1: " + productId1 + ", 상품2: " + productId2);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM wishlists WHERE user_id = ?", testUserId);
    }

    // ==================== toggleWishlist ====================

    @Test
    @DisplayName("toggleWishlist — 추가 → 제거 → 추가 토글 동작")
    void toggleWishlist_toggleBehavior() {
        // 1) 추가
        boolean added = wishlistService.toggleWishlist(testUserId, productId1);
        assertThat(added).isTrue();

        int count1 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishlists WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId1);
        assertThat(count1).isEqualTo(1);

        // 2) 제거
        boolean removed = wishlistService.toggleWishlist(testUserId, productId1);
        assertThat(removed).isFalse();

        int count2 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishlists WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId1);
        assertThat(count2).isZero();

        // 3) 다시 추가
        boolean addedAgain = wishlistService.toggleWishlist(testUserId, productId1);
        assertThat(addedAgain).isTrue();

        int count3 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wishlists WHERE user_id = ? AND product_id = ?",
                Integer.class, testUserId, productId1);
        assertThat(count3).isEqualTo(1);

        System.out.println("  [PASS] 토글: 추가(1) → 제거(0) → 추가(1)");
    }

    @Test
    @DisplayName("toggleWishlist 실패 — 존재하지 않는 상품")
    void toggleWishlist_nonExistentProduct_throwsException() {
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(product_id), 0) FROM products", Long.class);

        assertThatThrownBy(() -> wishlistService.toggleWishlist(testUserId, maxId + 9999))
                .isInstanceOf(ResourceNotFoundException.class);

        System.out.println("  [PASS] 존재하지 않는 상품 토글 → ResourceNotFoundException");
    }

    // ==================== isWishlisted ====================

    @Test
    @DisplayName("isWishlisted — 토글 전후 상태 반영")
    void isWishlisted_reflectsToggleState() {
        // 초기: 없음
        assertThat(wishlistService.isWishlisted(testUserId, productId1)).isFalse();

        // 추가 후
        wishlistService.toggleWishlist(testUserId, productId1);
        assertThat(wishlistService.isWishlisted(testUserId, productId1)).isTrue();

        // 제거 후
        wishlistService.toggleWishlist(testUserId, productId1);
        assertThat(wishlistService.isWishlisted(testUserId, productId1)).isFalse();

        System.out.println("  [PASS] isWishlisted: false → true → false");
    }

    @Test
    @DisplayName("isWishlisted — 다른 상품에는 영향 없음")
    void isWishlisted_isolatedPerProduct() {
        // 상품1만 추가
        wishlistService.toggleWishlist(testUserId, productId1);

        assertThat(wishlistService.isWishlisted(testUserId, productId1)).isTrue();
        assertThat(wishlistService.isWishlisted(testUserId, productId2)).isFalse();

        System.out.println("  [PASS] 상품별 독립성 확인");
    }

    // ==================== getWishlist ====================

    @Test
    @DisplayName("getWishlist — 페이징 조회, 상품 정보 포함")
    void getWishlist_returnsPagesWithProducts() {
        // Given: 2개 상품 추가
        wishlistService.toggleWishlist(testUserId, productId1);
        wishlistService.toggleWishlist(testUserId, productId2);

        // When
        Page<Wishlist> page = wishlistService.getWishlist(testUserId, PageRequest.of(0, 10));

        // Then
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);

        // Product가 JOIN FETCH되었는지 (Lazy 아닌지) 확인
        for (Wishlist w : page.getContent()) {
            assertThat(w.getProduct()).isNotNull();
            assertThat(w.getProduct().getProductName()).isNotEmpty();
        }

        System.out.println("  [PASS] 위시리스트 조회: " + page.getTotalElements() + "개, 상품 정보 포함");
    }

    @Test
    @DisplayName("getWishlist — 빈 위시리스트")
    void getWishlist_empty() {
        Page<Wishlist> page = wishlistService.getWishlist(testUserId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();

        System.out.println("  [PASS] 빈 위시리스트 조회");
    }

    @Test
    @DisplayName("toggleWishlist — 추가 후 제거하면 위시리스트에서 사라짐")
    void toggleWishlist_addThenRemove_disappearsFromList() {
        // Given: 추가
        wishlistService.toggleWishlist(testUserId, productId1);
        assertThat(wishlistService.getWishlist(testUserId, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);

        // When: 제거
        wishlistService.toggleWishlist(testUserId, productId1);

        // Then
        assertThat(wishlistService.getWishlist(testUserId, PageRequest.of(0, 10))
                .getTotalElements()).isZero();

        System.out.println("  [PASS] 추가 → 제거 후 목록에서 사라짐");
    }
}

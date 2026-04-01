package com.shop.domain.wishlist.entity;

import com.shop.domain.product.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wishlist 엔티티 단위 테스트.
 *
 * <p>생성자 초기화, getter 반환값, createdAt 자동 설정을 검증한다.</p>
 */
class WishlistEntityUnitTest {

    private Product createProduct() {
        return Product.create("테스트 상품", null, "설명",
                new java.math.BigDecimal("10000"), null, 100);
    }

    @Test
    @DisplayName("비즈니스 생성자 — userId, product, createdAt이 올바르게 초기화된다")
    void constructor_initializesAllFields() {
        // given
        Long userId = 42L;
        Product product = createProduct();
        LocalDateTime before = LocalDateTime.now();

        // when
        Wishlist wishlist = new Wishlist(userId, product);

        // then
        LocalDateTime after = LocalDateTime.now();
        assertThat(wishlist.getUserId()).isEqualTo(userId);
        assertThat(wishlist.getProduct()).isSameAs(product);
        assertThat(wishlist.getCreatedAt())
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
        // wishlistId는 JPA가 설정하므로 생성 직후에는 null
        assertThat(wishlist.getWishlistId()).isNull();
    }

    @Test
    @DisplayName("getUserId — 설정된 userId를 반환한다")
    void getUserId_returnsUserId() {
        Wishlist wishlist = new Wishlist(99L, createProduct());

        assertThat(wishlist.getUserId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("getProduct — 설정된 Product를 반환한다")
    void getProduct_returnsProduct() {
        Product product = createProduct();
        Wishlist wishlist = new Wishlist(1L, product);

        assertThat(wishlist.getProduct()).isSameAs(product);
    }

    @Test
    @DisplayName("getCreatedAt — 생성 시점의 LocalDateTime을 반환한다")
    void getCreatedAt_returnsNonNull() {
        Wishlist wishlist = new Wishlist(1L, createProduct());

        assertThat(wishlist.getCreatedAt()).isNotNull();
    }
}

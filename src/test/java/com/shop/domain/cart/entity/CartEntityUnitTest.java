package com.shop.domain.cart.entity;

import com.shop.domain.product.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Cart 엔티티 단위 테스트.
 *
 * <p>장바구니 엔티티의 생성자 초기화와 updateQuantity 메서드를 검증한다.
 * 기존 테스트에서 Cart 엔티티를 직접 생성하지 않아 LINE 82% / METHOD 67%였다.
 * 특히 getAddedAt(), getUpdatedAt(), getCartId() getter가 미커버였다.</p>
 */
class CartEntityUnitTest {

    @Test
    @DisplayName("생성자가 모든 필드를 올바르게 초기화한다")
    void constructor_initializesAllFields() {
        // given: Mock Product 객체
        Product product = mock(Product.class);

        // when: 장바구니 항목 생성
        Cart cart = new Cart(1L, product, 3);

        // then: 모든 필드가 정확히 설정
        assertThat(cart.getUserId()).isEqualTo(1L);
        assertThat(cart.getProduct()).isSameAs(product);
        assertThat(cart.getQuantity()).isEqualTo(3);
        assertThat(cart.getAddedAt()).isNotNull();
        assertThat(cart.getUpdatedAt()).isNotNull();
        // cartId는 JPA가 할당
        assertThat(cart.getCartId()).isNull();
    }

    @Test
    @DisplayName("updateQuantity가 수량과 updatedAt을 갱신한다")
    void updateQuantity_updatesQuantityAndTimestamp() {
        // given: 기존 장바구니 항목
        Product product = mock(Product.class);
        Cart cart = new Cart(1L, product, 2);
        java.time.LocalDateTime beforeUpdate = cart.getUpdatedAt();

        // when: 수량 변경
        cart.updateQuantity(5);

        // then: 수량이 변경되고 updatedAt이 갱신됨
        assertThat(cart.getQuantity()).isEqualTo(5);
        assertThat(cart.getUpdatedAt()).isAfterOrEqualTo(beforeUpdate);
    }
}

package com.shop.domain.cart.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CartService 분기 커버리지 보강 테스트.
 *
 * <p>기존 CartServiceIntegrationTest에서 다루지 않은 분기를 검증한다:
 * - getSelectedCartItems: cartItemIds가 null인 분기, 빈 리스트 분기
 * - addToCart: 기존 상품 존재 시 수량 누적 분기, 최대 개수 초과 분기
 * - updateQuantity: quantity <= 0 삭제 분기, 정상 수량 변경 분기</p>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceBranchTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CartService cartService;

    // ── getSelectedCartItems ──

    @Nested
    @DisplayName("getSelectedCartItems — null/empty 분기")
    class GetSelectedCartItemsTests {

        @Test
        @DisplayName("cartItemIds가 null이면 전체 장바구니 반환 (기존 동작 호환)")
        void nullCartItemIds_returnsAll() {
            // given
            List<Cart> allItems = List.of(mock(Cart.class));
            when(cartRepository.findByUserIdWithProduct(1L)).thenReturn(allItems);

            // when: null 전달 → getCartItems() 위임
            List<Cart> result = cartService.getSelectedCartItems(1L, null);

            // then
            assertThat(result).isSameAs(allItems);
            verify(cartRepository).findByUserIdWithProduct(1L);
        }

        @Test
        @DisplayName("cartItemIds가 빈 리스트이면 전체 장바구니 반환")
        void emptyCartItemIds_returnsAll() {
            // given
            List<Cart> allItems = List.of(mock(Cart.class));
            when(cartRepository.findByUserIdWithProduct(1L)).thenReturn(allItems);

            // when: 빈 리스트 → getCartItems() 위임
            List<Cart> result = cartService.getSelectedCartItems(1L, Collections.emptyList());

            // then
            assertThat(result).isSameAs(allItems);
        }

        @Test
        @DisplayName("cartItemIds가 있으면 선택된 항목만 조회")
        void withCartItemIds_returnsSelected() {
            // given
            List<Long> ids = List.of(1L, 2L);
            List<Cart> selected = List.of(mock(Cart.class));
            when(cartRepository.findByUserIdAndCartIdIn(1L, ids)).thenReturn(selected);

            // when
            List<Cart> result = cartService.getSelectedCartItems(1L, ids);

            // then
            assertThat(result).isSameAs(selected);
        }
    }

    // ── addToCart ──

    @Nested
    @DisplayName("addToCart — 분기 테스트")
    class AddToCartTests {

        @Test
        @DisplayName("기존 상품이 있으면 수량 누적")
        void existingProduct_accumulatesQuantity() {
            // given
            Product product = mock(Product.class);
            when(product.getIsActive()).thenReturn(true);
            when(product.getStockQuantity()).thenReturn(100);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));

            Cart existingCart = mock(Cart.class);
            when(existingCart.getQuantity()).thenReturn(3);
            when(cartRepository.findByUserIdAndProduct_ProductId(1L, 10L))
                    .thenReturn(Optional.of(existingCart));

            // when: 기존 3개 + 추가 2개 = 5개
            cartService.addToCart(1L, 10L, 2);

            // then: updateQuantity(5) 호출
            verify(existingCart).updateQuantity(5);
        }

        @Test
        @DisplayName("장바구니 최대 개수(50) 초과 시 BusinessException")
        void maxCartItems_throwsException() {
            // given
            Product product = mock(Product.class);
            when(product.getIsActive()).thenReturn(true);
            when(product.getStockQuantity()).thenReturn(100);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(cartRepository.findByUserIdAndProduct_ProductId(1L, 10L))
                    .thenReturn(Optional.empty());
            when(cartRepository.countByUserId(1L)).thenReturn(50);

            // when & then: 최대 개수 초과
            assertThatThrownBy(() -> cartService.addToCart(1L, 10L, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("최대 50개");
        }

        @Test
        @DisplayName("수량 0 이하 → INVALID_QUANTITY 예외")
        void zeroQuantity_throwsException() {
            assertThatThrownBy(() -> cartService.addToCart(1L, 10L, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1개 이상");
        }
    }

    // ── updateQuantity ──

    @Nested
    @DisplayName("updateQuantity — 삭제/변경 분기")
    class UpdateQuantityTests {

        @Test
        @DisplayName("quantity <= 0이면 장바구니 항목 삭제")
        void zeroQuantity_deletesCartItem() {
            // given
            Cart cart = mock(Cart.class);
            when(cartRepository.findByUserIdAndProduct_ProductId(1L, 10L))
                    .thenReturn(Optional.of(cart));

            // when: 수량 0 → 삭제
            cartService.updateQuantity(1L, 10L, 0);

            // then
            verify(cartRepository).delete(cart);
        }

        @Test
        @DisplayName("quantity > 0이면 수량 변경")
        void positiveQuantity_updatesQuantity() {
            // given
            Cart cart = mock(Cart.class);
            when(cartRepository.findByUserIdAndProduct_ProductId(1L, 10L))
                    .thenReturn(Optional.of(cart));

            Product product = mock(Product.class);
            when(product.getIsActive()).thenReturn(true);
            when(product.getStockQuantity()).thenReturn(100);
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));

            // when
            cartService.updateQuantity(1L, 10L, 5);

            // then
            verify(cart).updateQuantity(5);
        }

        @Test
        @DisplayName("장바구니 항목 미존재 → ResourceNotFoundException")
        void cartItemNotFound_throwsException() {
            // given
            when(cartRepository.findByUserIdAndProduct_ProductId(1L, 10L))
                    .thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> cartService.updateQuantity(1L, 10L, 5))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}

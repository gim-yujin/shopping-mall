package com.shop.domain.cart.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceUnitTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CartService cartService;

    @Test
    @DisplayName("0개 이하 수량은 장바구니 추가가 거부되어야 한다")
    void addToCart_rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> cartService.addToCart(1L, 10L, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("수량은 1개 이상이어야 합니다");

        verifyNoInteractions(productRepository);
        verify(cartRepository, never()).save(any());
    }

    @Test
    @DisplayName("기존 장바구니 수량 + 추가 수량이 재고를 초과하면 실패해야 한다")
    void addToCart_rejectsWhenRequestedQuantityExceedsStock() {
        Product product = mock(Product.class);
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(product.getIsActive()).thenReturn(true);
        when(product.getStockQuantity()).thenReturn(5);

        Cart existingCart = new Cart(1L, product, 4);
        when(cartRepository.findByUserIdAndProduct_ProductId(1L, 10L)).thenReturn(Optional.of(existingCart));

        assertThatThrownBy(() -> cartService.addToCart(1L, 10L, 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("재고가 부족합니다");

        verify(cartRepository).acquireUserCartLock(1L);
        verify(cartRepository, never()).save(any());
    }
}

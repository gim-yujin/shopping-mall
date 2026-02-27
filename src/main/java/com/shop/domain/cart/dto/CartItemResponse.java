package com.shop.domain.cart.dto;

import com.shop.domain.cart.entity.Cart;

import java.math.BigDecimal;

/**
 * [P1-6] 장바구니 항목 응답 DTO.
 */
public record CartItemResponse(
        Long cartId,
        Long productId,
        String productName,
        BigDecimal price,
        String thumbnailUrl,
        int quantity,
        BigDecimal subtotal
) {
    public static CartItemResponse from(Cart cart) {
        return new CartItemResponse(
                cart.getCartId(),
                cart.getProduct().getProductId(),
                cart.getProduct().getProductName(),
                cart.getProduct().getPrice(),
                cart.getProduct().getThumbnailUrl(),
                cart.getQuantity(),
                cart.getProduct().getPrice().multiply(BigDecimal.valueOf(cart.getQuantity()))
        );
    }
}

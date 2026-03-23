package com.shop.domain.order.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.order.dto.OrderCreateRequest;
import com.shop.global.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
class OrderCartSelectionResolver {

    private final CartRepository cartRepository;

    OrderCartSelectionResolver(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    CartSelection resolve(Long userId, OrderCreateRequest request) {
        cartRepository.acquireUserCartLock(userId);

        List<Cart> cartItems;
        boolean isPartialOrder;
        if (request.cartItemIds() != null && !request.cartItemIds().isEmpty()) {
            Set<Long> requestedCartItemIds = new LinkedHashSet<>(request.cartItemIds());
            cartItems = cartRepository.findByUserIdAndCartIdIn(userId, new ArrayList<>(requestedCartItemIds));
            Set<Long> foundCartItemIds = cartItems.stream()
                    .map(Cart::getCartId)
                    .collect(java.util.stream.Collectors.toSet());

            if (!requestedCartItemIds.equals(foundCartItemIds)) {
                throw new BusinessException(
                        "INVALID_CART_SELECTION",
                        "유효하지 않거나 접근 불가한 장바구니 항목이 포함됨"
                );
            }
            isPartialOrder = true;
        } else {
            cartItems = cartRepository.findByUserIdWithProduct(userId);
            isPartialOrder = false;
        }

        if (cartItems.isEmpty()) {
            throw new BusinessException("EMPTY_CART", "장바구니가 비어있습니다.");
        }

        // 데드락 예방을 위해 상품 ID 순으로 정렬 (자원 획득 순서 일관성 유지)
        cartItems.sort(java.util.Comparator.comparing(cart -> cart.getProduct().getProductId()));
        return new CartSelection(cartItems, isPartialOrder);
    }

    record CartSelection(List<Cart> items, boolean isPartialOrder) {
    }
}

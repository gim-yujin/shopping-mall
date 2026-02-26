package com.shop.domain.cart.service;

import com.shop.domain.cart.entity.Cart;
import com.shop.domain.cart.repository.CartRepository;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
    }

    public List<Cart> getCartItems(Long userId) {
        return cartRepository.findByUserIdWithProduct(userId);
    }

    private static final int MAX_CART_ITEMS = 50;

    @Transactional
    public void addToCart(Long userId, Long productId, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("INVALID_QUANTITY", "수량은 1개 이상이어야 합니다.");
        }

        Product product = findProduct(productId);

        // 사용자별 Advisory Lock → 같은 사용자의 동시 장바구니 작업을 직렬화
        // 트랜잭션 종료 시 자동 해제됨
        cartRepository.acquireUserCartLock(userId);

        Optional<Cart> existing = cartRepository.findByUserIdAndProduct_ProductId(userId, productId);
        int requestedQuantity = existing.map(cart -> cart.getQuantity() + quantity).orElse(quantity);
        validateProductForQuantity(product, requestedQuantity);

        if (existing.isPresent()) {
            existing.get().updateQuantity(requestedQuantity);
        } else {
            if (cartRepository.countByUserId(userId) >= MAX_CART_ITEMS) {
                throw new BusinessException("CART_LIMIT",
                        "장바구니에는 최대 " + MAX_CART_ITEMS + "개의 상품만 담을 수 있습니다.");
            }
            cartRepository.save(new Cart(userId, product, quantity));
        }
    }

    @Transactional
    public void updateQuantity(Long userId, Long productId, int quantity) {
        Cart cart = cartRepository.findByUserIdAndProduct_ProductId(userId, productId)
                .orElseThrow(() -> new ResourceNotFoundException("장바구니 항목", productId));
        if (quantity <= 0) {
            cartRepository.delete(cart);
        } else {
            Product product = findProduct(productId);
            validateProductForQuantity(product, quantity);
            cart.updateQuantity(quantity);
        }
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
    }

    private void validateProductForQuantity(Product product, int quantity) {
        if (!product.getIsActive()) {
            throw new BusinessException("INACTIVE", "판매 중지된 상품입니다.");
        }

        if (product.getStockQuantity() < quantity) {
            throw new BusinessException("STOCK", "재고가 부족합니다.");
        }
    }

    @Transactional
    public void removeFromCart(Long userId, Long productId) {
        cartRepository.deleteByUserIdAndProduct_ProductId(userId, productId);
    }

    @Transactional
    public void clearCart(Long userId) {
        cartRepository.deleteByUserId(userId);
    }

    public int getCartCount(Long userId) {
        return cartRepository.countByUserId(userId);
    }

    public BigDecimal calculateTotal(List<Cart> items) {
        return items.stream()
                .map(c -> c.getProduct().getPrice().multiply(BigDecimal.valueOf(c.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

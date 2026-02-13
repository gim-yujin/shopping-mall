package com.shop.domain.wishlist.service;

import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.wishlist.entity.Wishlist;
import com.shop.domain.wishlist.repository.WishlistRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;

    public WishlistService(WishlistRepository wishlistRepository, ProductRepository productRepository) {
        this.wishlistRepository = wishlistRepository;
        this.productRepository = productRepository;
    }

    public Page<Wishlist> getWishlist(Long userId, Pageable pageable) {
        return wishlistRepository.findByUserIdWithProduct(userId, pageable);
    }

    public boolean isWishlisted(Long userId, Long productId) {
        return wishlistRepository.existsByUserIdAndProduct_ProductId(userId, productId);
    }

    @Transactional
    public boolean toggleWishlist(Long userId, Long productId) {
        // 먼저 삭제 시도 (네이티브 SQL → 행이 없으면 0 반환, 예외 없음)
        int deleted = wishlistRepository.deleteByUserIdAndProductIdNative(userId, productId);
        if (deleted > 0) {
            return false; // 삭제됨
        }

        // 삭제할 것이 없으면 삽입 시도 (ON CONFLICT DO NOTHING → 중복이면 0 반환, 예외 없음)
        if (!productRepository.existsById(productId)) {
            throw new ResourceNotFoundException("상품", productId);
        }
        int inserted = wishlistRepository.insertIgnoreConflict(userId, productId);
        // inserted=1이면 추가됨, inserted=0이면 다른 스레드가 이미 추가함 (어느 쪽이든 "존재" 상태)
        return true;
    }
}

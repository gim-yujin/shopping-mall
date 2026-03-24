package com.shop.domain.wishlist.service;

import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.wishlist.entity.Wishlist;
import com.shop.domain.wishlist.repository.WishlistRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.hibernate.Hibernate;
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

    // [Phase 20] Wishlist는 Page 반환이므로 컬렉션 JOIN FETCH를 사용할 수 없다.
    // (Hibernate가 페이징을 인메모리로 수행하여 전체 데이터를 로딩하게 됨)
    // 대신 트랜잭션 내에서 images를 명시적으로 초기화하여
    // OSIV=off 환경에서도 getThumbnailUrl()이 실제 썸네일을 반환하도록 한다.
    // Product.images에 @BatchSize(size=30)가 적용되어 있으므로
    // 페이지 내 모든 상품의 이미지가 1회 IN 쿼리로 일괄 로딩된다.
    public Page<Wishlist> getWishlist(Long userId, Pageable pageable) {
        Page<Wishlist> page = wishlistRepository.findByUserIdWithProduct(userId, pageable);
        page.getContent().forEach(w -> Hibernate.initialize(w.getProduct().getImages()));
        return page;
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
        wishlistRepository.insertIgnoreConflict(userId, productId);
        // 반환값 1이면 추가됨, 0이면 다른 스레드가 이미 추가함 (어느 쪽이든 "존재" 상태)
        return true;
    }
}

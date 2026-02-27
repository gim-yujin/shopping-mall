package com.shop.domain.product.repository;

import com.shop.domain.product.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
    List<ProductImage> findByProduct_ProductIdOrderByImageOrderAsc(Long productId);

    /**
     * [P2-9] 특정 상품의 전체 이미지 삭제.
     * 상품 수정 시 기존 이미지를 전량 교체하는 전략에서 사용한다.
     */
    void deleteByProduct_ProductId(Long productId);

    /**
     * [P2-9] 특정 상품의 이미지 개수 조회.
     */
    int countByProduct_ProductId(Long productId);
}

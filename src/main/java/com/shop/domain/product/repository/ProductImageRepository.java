package com.shop.domain.product.repository;

import com.shop.domain.product.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
    List<ProductImage> findByProduct_ProductIdOrderByImageOrderAsc(Long productId);
}

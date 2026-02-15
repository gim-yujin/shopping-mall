package com.shop.domain.product.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ViewCountService viewCountService;

    public ProductService(ProductRepository productRepository, ViewCountService viewCountService) {
        this.productRepository = productRepository;
        this.viewCountService = viewCountService;
    }

    public Product findById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
    }

    /**
     * 상품 상세 조회 + 조회수 증가 (비동기).
     * OSIV off 환경: category를 JOIN FETCH로 즉시 로딩.
     * viewCount UPDATE는 비동기 스레드에서 별도 트랜잭션으로 처리 → 읽기 전용 트랜잭션 유지.
     */
    public Product findByIdAndIncrementView(Long productId) {
        Product product = productRepository.findByIdWithCategory(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        viewCountService.incrementAsync(productId);  // fire-and-forget
        return product;
    }

    public Page<Product> findByCategory(Integer categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable);
    }

    public Page<Product> findByCategoryIds(List<Integer> categoryIds, Pageable pageable) {
        return productRepository.findByCategoryIds(categoryIds, pageable);
    }

    public Page<Product> search(String keyword, Pageable pageable) {
        Page<Product> results = productRepository.searchByKeyword(keyword, pageable);
        if (results.isEmpty()) {
            results = productRepository.searchByKeywordLike(keyword, pageable);
        }
        return results;
    }

    @Cacheable(value = "bestSellers", key = "'home'")
    public Page<Product> getBestSellers(Pageable pageable) {
        return productRepository.findBestSellers(pageable);
    }

    @Cacheable(value = "newArrivals", key = "'home'")
    public Page<Product> getNewArrivals(Pageable pageable) {
        return productRepository.findNewArrivals(pageable);
    }

    @Cacheable(value = "deals", key = "'home'")
    public Page<Product> getDeals(Pageable pageable) {
        return productRepository.findDeals(pageable);
    }

    public Page<Product> findAll(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    public Page<Product> findAllSorted(int page, int size, String sort) {
        Sort sortObj = switch (sort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "newest" -> Sort.by("createdAt").descending();
            case "rating" -> Sort.by("ratingAvg").descending();
            case "review" -> Sort.by("reviewCount").descending();
            default -> Sort.by("salesCount").descending();
        };
        return productRepository.findAll(PageRequest.of(page, size, sortObj));
    }
}

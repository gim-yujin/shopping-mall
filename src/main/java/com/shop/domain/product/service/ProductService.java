package com.shop.domain.product.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.CacheEvict;
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
     * 캐시 히트 시 메서드 본문이 실행되지 않으므로:
     *   - DB 조회 0회, viewCount 증가는 캐시 미스 시에만 발생
     *   - TTL 2분 = 상품당 2분에 1회만 DB 접근
     */
    @Cacheable(value = "productDetail", key = "#productId")
    public Product findByIdAndIncrementView(Long productId) {
        Product product = productRepository.findByIdWithCategory(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        viewCountService.incrementAsync(productId);
        return product;
    }

    @Transactional
    @CacheEvict(value = "productDetail", key = "#productId")
    public void evictProductDetailCache(Long productId) {
        // 캐시 evict 전용 진입점
    }

    public Page<Product> findByCategory(Integer categoryId, Pageable pageable) {
        return productRepository.findByCategoryId(categoryId, pageable);
    }

    @Cacheable(value = "categoryProducts",
               key = "#categoryIds.hashCode() + ':' + #pageable.pageNumber + ':' + #pageable.pageSize + ':' + #pageable.sort.toString()")
    public Page<Product> findByCategoryIds(List<Integer> categoryIds, Pageable pageable) {
        return productRepository.findByCategoryIds(categoryIds, pageable);
    }

    @Cacheable(value = "categoryProducts",
               key = "#categoryIds.hashCode() + ':' + #page + ':' + #size + ':' + #sort")
    public Page<Product> findByCategoryIdsSorted(List<Integer> categoryIds, int page, int size, String sort) {
        return productRepository.findByCategoryIds(categoryIds, PageRequest.of(page, size, mapSort(sort)));
    }

    @Cacheable(value = "searchResults",
               key = "T(com.shop.global.cache.CacheKeyGenerator).pageableWithPrefix(#keyword, #pageable)")
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

    @Cacheable(value = "productList", key = "#page + ':' + #size + ':' + #sort")
    public Page<Product> findAllSorted(int page, int size, String sort) {
        return productRepository.findByIsActiveTrue(PageRequest.of(page, size, mapSort(sort)));
    }

    private Sort mapSort(String sort) {
        return switch (sort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "newest" -> Sort.by("createdAt").descending();
            case "rating" -> Sort.by("ratingAvg").descending();
            case "review" -> Sort.by("reviewCount").descending();
            default -> Sort.by("salesCount").descending();
        };
    }
}

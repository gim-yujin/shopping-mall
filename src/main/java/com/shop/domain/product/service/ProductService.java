package com.shop.domain.product.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ViewCountService viewCountService;
    private final CategoryService categoryService;

    public ProductService(ProductRepository productRepository, ViewCountService viewCountService,
                          CategoryService categoryService) {
        this.productRepository = productRepository;
        this.viewCountService = viewCountService;
        this.categoryService = categoryService;
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
               key = "#categoryIds.toString() + ':' + #pageable.pageNumber + ':' + #pageable.pageSize + ':' + #pageable.sort.toString()")
    public Page<Product> findByCategoryIds(List<Integer> categoryIds, Pageable pageable) {
        return productRepository.findByCategoryIds(categoryIds, pageable);
    }

    /**
     * 카테고리별 상품 목록 조회 (정렬 포함).
     * 입력 파라미터는 컨트롤러에서 정규화된 상태로 전달된다.
     */
    @Cacheable(value = "categoryProducts",
               key = "#categoryIds.toString() + ':' + #page + ':' + #size + ':' + #sort")
    public Page<Product> findByCategoryIdsSorted(List<Integer> categoryIds, int page, int size, String sort) {
        return productRepository.findByCategoryIds(categoryIds,
                PageRequest.of(page, size, PagingParams.toProductSort(sort)));
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

    @Cacheable(value = "bestSellers", key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)")
    public Page<Product> getBestSellers(Pageable pageable) {
        return productRepository.findBestSellers(pageable);
    }

    @Cacheable(value = "newArrivals", key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)")
    public Page<Product> getNewArrivals(Pageable pageable) {
        return productRepository.findNewArrivals(pageable);
    }

    @Cacheable(value = "deals", key = "T(com.shop.global.cache.CacheKeyGenerator).pageable(#pageable)")
    public Page<Product> getDeals(Pageable pageable) {
        return productRepository.findDeals(pageable);
    }

    public Page<Product> findAll(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    /**
     * 상품 전체 목록 조회 (정렬 포함).
     * 입력 파라미터는 컨트롤러에서 정규화된 상태로 전달된다.
     */
    @Cacheable(value = "productList", key = "#page + ':' + #size + ':' + #sort")
    public Page<Product> findAllSorted(int page, int size, String sort) {
        return productRepository.findByIsActiveTrue(
                PageRequest.of(page, size, PagingParams.toProductSort(sort)));
    }

    // ────────────────────────────────────────────
    // Admin CRUD
    // ────────────────────────────────────────────

    /**
     * 관리자용 상품 상세 조회 (카테고리 fetch join, 비활성 포함).
     */
    public Product findByIdForAdmin(Long productId) {
        return productRepository.findByIdWithCategory(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
    }

    /**
     * 관리자용 상품 목록 (비활성 포함).
     */
    public Page<Product> findAllForAdmin(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "productList", allEntries = true),
            @CacheEvict(value = "categoryProducts", allEntries = true),
            @CacheEvict(value = "searchResults", allEntries = true),
            @CacheEvict(value = "bestSellers", allEntries = true),
            @CacheEvict(value = "newArrivals", allEntries = true),
            @CacheEvict(value = "deals", allEntries = true)
    })
    public Product createProduct(AdminProductRequest request) {
        Category category = categoryService.findById(request.getCategoryId());
        Product product = Product.create(
                request.getProductName(),
                category,
                request.getDescription(),
                request.getPrice(),
                request.getOriginalPrice(),
                request.getStockQuantity()
        );
        return productRepository.save(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "productDetail", key = "#productId"),
            @CacheEvict(value = "productList", allEntries = true),
            @CacheEvict(value = "categoryProducts", allEntries = true),
            @CacheEvict(value = "searchResults", allEntries = true),
            @CacheEvict(value = "bestSellers", allEntries = true),
            @CacheEvict(value = "newArrivals", allEntries = true),
            @CacheEvict(value = "deals", allEntries = true)
    })
    public Product updateProduct(Long productId, AdminProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        Category category = categoryService.findById(request.getCategoryId());
        product.update(
                request.getProductName(),
                category,
                request.getDescription(),
                request.getPrice(),
                request.getOriginalPrice(),
                request.getStockQuantity()
        );
        return product;
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "productDetail", key = "#productId"),
            @CacheEvict(value = "productList", allEntries = true),
            @CacheEvict(value = "categoryProducts", allEntries = true),
            @CacheEvict(value = "searchResults", allEntries = true),
            @CacheEvict(value = "bestSellers", allEntries = true),
            @CacheEvict(value = "newArrivals", allEntries = true),
            @CacheEvict(value = "deals", allEntries = true)
    })
    public void toggleProductActive(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        product.toggleActive();
    }
}

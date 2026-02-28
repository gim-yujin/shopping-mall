package com.shop.domain.product.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.entity.ProductImage;
import com.shop.domain.product.repository.ProductImageRepository;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.cache.CacheKeyGenerator;
import com.shop.global.common.PagingParams;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ViewCountService viewCountService;
    private final CategoryService categoryService;

    public ProductService(ProductRepository productRepository,
                          ProductImageRepository productImageRepository,
                          ViewCountService viewCountService,
                          CategoryService categoryService) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.viewCountService = viewCountService;
        this.categoryService = categoryService;
    }

    public Product findById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
    }

    /**
     * 상품 상세 조회 (캐시 적용).
     *
     * [P0 BUG FIX] 조회수 증가 로직을 이 메서드에서 분리함.
     *
     * 기존 문제:
     *   @Cacheable 메서드 안에서 viewCountService.incrementAsync()를 호출했으므로,
     *   캐시 히트 시 메서드 본문 자체가 실행되지 않아 조회수가 캐시 미스 시에만 증가했다.
     *   TTL 2분 기준으로 상품당 2분에 1회만 조회수가 오르는 결과를 초래했다.
     *
     * 수정:
     *   캐시 메서드는 순수 조회만 담당하고, 조회수 증가는 컨트롤러에서 별도 호출한다.
     *   이렇게 하면 캐시 히트 여부와 무관하게 매 요청마다 조회수가 정확히 증가한다.
     *
     * [P2-7] 반환 타입을 Product 엔티티 → CachedProductDetail 불변 DTO로 전환.
     * Caffeine 캐시에 JPA 엔티티 대신 불변 DTO를 저장하여 객체 참조 공유로 인한
     * 데이터 오염 가능성을 근본적으로 차단한다. 자세한 설명은 CachedProductDetail Javadoc 참조.
     *
     * @see CachedProductDetail — 캐시 저장 DTO, 변경 불가
     */
    @Cacheable(value = "productDetail", key = "#productId")
    public CachedProductDetail findByIdCached(Long productId) {
        Product product = productRepository.findByIdWithCategory(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        return CachedProductDetail.from(product);
    }

    /**
     * @deprecated findByIdCached + ViewCountService.incrementAsync 조합으로 대체됨.
     *             기존 호출처 호환을 위해 유지하되, 신규 코드에서는 사용하지 말 것.
     */
    @Deprecated(since = "P0 viewCount fix", forRemoval = true)
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
               key = "#root.target.searchCacheKey(#keyword, #pageable)")
    public Page<Product> search(String keyword, Pageable pageable) {
        String normalizedKeyword = normalizeSearchKeyword(keyword);

        Page<Product> results;
        try {
            results = productRepository.searchByKeyword(normalizedKeyword, pageable);
        } catch (DataAccessException e) {
            log.warn("정규 검색(FTS) 실패로 LIKE 검색으로 폴백합니다. keyword={}", normalizedKeyword, e);
            return productRepository.searchByKeywordLike(normalizedKeyword, pageable);
        }

        if (results.isEmpty()) {
            results = productRepository.searchByKeywordLike(normalizedKeyword, pageable);
        }
        return results;
    }

    String normalizeSearchKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    String searchCacheKey(String keyword, Pageable pageable) {
        return CacheKeyGenerator.pageableWithPrefix(normalizeSearchKeyword(keyword), pageable);
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
        Product savedProduct = productRepository.save(product);

        // [P2-9] 이미지 URL 목록이 있으면 ProductImage 엔티티로 저장
        saveProductImages(savedProduct, request.getImageUrls());

        return savedProduct;
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

        // [P2-9] 이미지 전량 교체 전략: 기존 이미지를 모두 삭제 후 새 목록으로 재생성.
        // 부분 수정(개별 추가/삭제/순서 변경)보다 구현이 단순하며,
        // 상품당 이미지 수가 적어(평균 3장) 성능 영향이 미미하다.
        if (request.getImageUrls() != null) {
            productImageRepository.deleteByProduct_ProductId(productId);
            saveProductImages(product, request.getImageUrls());
        }

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

    /**
     * [P2-9] 이미지 URL 목록에서 ProductImage 엔티티를 생성하여 저장한다.
     * 첫 번째 이미지를 썸네일로 지정하고, 나머지는 순서대로 imageOrder를 부여한다.
     *
     * @param product   이미지를 연결할 상품
     * @param imageUrls 이미지 URL 목록 (null 또는 빈 리스트면 무시)
     */
    private void saveProductImages(Product product, java.util.List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }
        for (int i = 0; i < imageUrls.size(); i++) {
            String url = imageUrls.get(i);
            if (url == null || url.isBlank()) continue;
            boolean isThumbnail = (i == 0);
            productImageRepository.save(new ProductImage(product, url.trim(), i, isThumbnail));
        }
    }

    /**
     * [P2-9] 상품의 이미지 목록을 조회한다.
     *
     * @param productId 상품 ID
     * @return 정렬된 이미지 목록
     */
    public java.util.List<ProductImage> getProductImages(Long productId) {
        return productImageRepository.findByProduct_ProductIdOrderByImageOrderAsc(productId);
    }
}

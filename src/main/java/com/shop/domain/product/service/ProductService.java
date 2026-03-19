package com.shop.domain.product.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.inventory.service.InventoryService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.entity.ProductImage;
import com.shop.domain.product.repository.ProductImageRepository;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.BusinessException;
import com.shop.global.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ViewCountService viewCountService;
    private final CategoryService categoryService;
    private final InventoryService inventoryService;

    public ProductService(ProductRepository productRepository,
                          ProductImageRepository productImageRepository,
                          ViewCountService viewCountService,
                          CategoryService categoryService,
                          InventoryService inventoryService) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.viewCountService = viewCountService;
        this.categoryService = categoryService;
        this.inventoryService = inventoryService;
    }

    public Product findById(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
    }

    /**
     * [Phase 18] 읽기 전용 메서드들은 ProductQueryService로 이동됨.
     *
     * <p>CQRS 읽기 모델 분리에 의해 다음 메서드들이 ProductQueryService로 이전되었다:</p>
     * <ul>
     *   <li>findByIdCached → ProductQueryService.findByIdCached</li>
     *   <li>getBestSellers/getNewArrivals/getDeals → ProductQueryService</li>
     *   <li>findAllSorted/findByCategoryIdsSorted → ProductQueryService</li>
     *   <li>search → ProductQueryService.search</li>
     * </ul>
     * <p>@CacheEvict는 캐시 이름으로 동작하므로 이 서비스에 그대로 남아 있다.</p>
     */

    /** @deprecated Phase 18에서 ProductQueryService.findByIdCached()로 대체됨. */
    @Deprecated(since = "Phase 18", forRemoval = true)
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

    /**
     * 관리자 상품 수정.
     *
     * [P1 BUG FIX] 재고 변경 시 ProductInventoryHistory 미기록 및 캐시 무효화 누락 수정.
     *
     * 기존 문제: product.update()가 stockQuantity를 직접 덮어쓰면서
     * ProductInventoryHistory에 변경 이력이 남지 않았다.
     * 감사(audit) 시 관리자의 재고 조정 내역을 추적할 수 없고,
     * Outbox 이벤트가 발행되지 않아 상품 상세 캐시가 갱신되지 않았다.
     *
     * 수정: 재고 수량이 변경된 경우에만 InventoryService.adjustStock()을
     * 호출하여 비관적 잠금 + 이력 기록 + Outbox 이벤트 발행을 수행한다.
     * product.update()에는 원래 재고값을 전달하여 이중 변경을 방지한다.
     *
     * [Phase 4] 낙관적 잠금 충돌 감지.
     *
     * 문제: 두 관리자가 동시에 같은 상품을 수정하면 나중에 저장한 쪽이
     * 먼저 저장한 변경을 무음으로 덮어쓴다(Lost Update).
     * 또한 관리자가 상품을 로드한 시점과 저장 시점 사이에 주문으로 인해
     * 재고가 변경되었을 수 있다.
     *
     * 해결: Product 엔티티에 @Version을 추가하여 UPDATE 시 버전 불일치를 감지한다.
     * 충돌 시 ObjectOptimisticLockingFailureException → BusinessException으로 변환하여
     * 관리자에게 의미 있는 에러 메시지를 전달한다.
     */
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
        try {
            return updateProductInternal(productId, request);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("상품 수정 중 낙관적 잠금 충돌 - productId={}, entity={}",
                    productId, e.getPersistentClassName());
            throw new BusinessException("CONCURRENT_MODIFICATION",
                    "다른 관리자 또는 주문 처리에 의해 상품 정보가 변경되었습니다. 페이지를 새로고침 후 다시 시도해주세요.");
        }
    }

    private Product updateProductInternal(Long productId, AdminProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
        Category category = categoryService.findById(request.getCategoryId());

        // [P1 FIX] 재고 변경분 감지: 변경 전 재고를 보존하고 차이가 있으면 이력 기록
        int currentStock = product.getStockQuantity();
        int requestedStock = request.getStockQuantity();
        int stockDelta = requestedStock - currentStock;

        // product.update()에는 현재 재고를 전달하여 재고값은 변경하지 않음
        // 재고 변경은 InventoryService를 통해 이력+캐시 무효화와 함께 처리
        product.update(
                request.getProductName(),
                category,
                request.getDescription(),
                request.getPrice(),
                request.getOriginalPrice(),
                stockDelta != 0 ? currentStock : requestedStock
        );

        // [Phase 4] @Version 충돌을 트랜잭션 커밋 전에 감지하기 위해 명시적 flush.
        // flush()를 호출하지 않으면 JPA dirty checking이 트랜잭션 커밋 시점에 실행되어
        // OptimisticLockException이 서비스 계층의 try-catch 밖(AOP 프록시)에서 발생한다.
        // 명시적 flush로 UPDATE ... WHERE version = ?를 즉시 실행하여
        // 충돌을 서비스 계층에서 잡아 의미 있는 BusinessException으로 변환할 수 있다.
        productRepository.flush();

        // 재고 변경분이 있을 때만 InventoryService 경유 — 이력 기록 + Outbox 이벤트 발행
        if (stockDelta != 0) {
            inventoryService.adjustStock(productId, stockDelta, "ADMIN_EDIT", null);
        }

        // [P2-9] 이미지 전량 교체 전략: 기존 이미지를 모두 삭제 후 새 목록으로 재생성.
        // 부분 수정(개별 추가/삭제/순서 변경)보다 구현이 단순하며,
        // 상품당 이미지 수가 적어(평균 3장) 성능 영향이 미미하다.
        if (request.getImageUrls() != null) {
            productImageRepository.deleteByProduct_ProductId(productId);
            saveProductImages(product, request.getImageUrls());
        }

        return product;
    }

    /**
     * [Phase 4] 낙관적 잠금 충돌 시 의미 있는 에러 메시지로 변환.
     *
     * 관리자가 토글 버튼을 누르는 시점에 다른 트랜잭션(주문/다른 관리자)이
     * 같은 상품을 수정했으면 충돌이 발생한다. 빈도가 낮으므로 재시도 대신
     * 사용자에게 새로고침을 안내한다.
     */
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
        try {
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("상품", productId));
            product.toggleActive();
            // [Phase 4] 커밋 전 버전 충돌 감지를 위한 명시적 flush
            productRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("상품 활성/비활성 토글 중 낙관적 잠금 충돌 - productId={}", productId);
            throw new BusinessException("CONCURRENT_MODIFICATION",
                    "다른 작업에 의해 상품 정보가 변경되었습니다. 페이지를 새로고침 후 다시 시도해주세요.");
        }
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
            if (url == null || url.isBlank()) {
                continue;
            }
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

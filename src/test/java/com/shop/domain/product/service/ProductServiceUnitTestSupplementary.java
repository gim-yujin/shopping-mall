package com.shop.domain.product.service;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.port.InventoryAdjustmentPort;
import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.repository.ProductImageRepository;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ProductService 추가 단위 테스트
 * — 미커버 메서드: findByCategory, findByCategoryIds, getBestSellers, getNewArrivals, getDeals
 * — 미커버 분기: findAllSorted 전체 sort 경로, search 정규 검색 성공 경로
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceUnitTestSupplementary {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private CategoryService categoryService;

    // [P1 FIX] 상품 수정 시 재고 변경분 이력 기록을 위해 추가
    @Mock
    private InventoryAdjustmentPort inventoryAdjustmentPort;

    private ProductService productService;

    // [Phase 18] 읽기 메서드가 ProductQueryService로 이동됨
    private ProductQueryService productQueryService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, productImageRepository, categoryService, inventoryAdjustmentPort);
        productQueryService = new ProductQueryService(productRepository, newTestCacheManager());
    }

    /** [Phase 21] count 캐시 분리에 맞춰 테스트용 CacheManager 제공. */
    private static CacheManager newTestCacheManager() {
        SimpleCacheManager mgr = new SimpleCacheManager();
        mgr.setCaches(List.of(
                new CaffeineCache("productListCount", Caffeine.newBuilder().maximumSize(10).build()),
                new CaffeineCache("categoryProductsCount", Caffeine.newBuilder().maximumSize(500).build())
        ));
        mgr.initializeCaches();
        return mgr;
    }

    /**
     * [Phase 18] ProductListReadModel.fromNativeRow()에 필요한 Object[] 픽스처 생성.
     * v_product_list 뷰의 컬럼 순서와 일치해야 한다.
     */
    /**
     * [Phase 18] 네이티브 SQL은 java.sql.Timestamp를 반환하므로 Timestamp으로 생성한다.
     */
    private Object[] createNativeRow(Long productId, String productName) {
        return new Object[]{
                productId, productName,
                new BigDecimal("10000"), new BigDecimal("12000"),
                new BigDecimal("4.50"), 25, 100,
                1, "전자기기",
                java.sql.Timestamp.valueOf(LocalDateTime.now()), "/images/thumb.jpg", true,
                50
        };
    }

    // ==================== 미커버 메서드 5개 ====================

    // [Phase 18/21] findByCategoryIdsSorted — content/count 분리 (findByCategoryIdsFlatContent + countActiveByCategoryIds)
    @Test
    @DisplayName("findByCategoryIdsSorted — 카테고리 ID로 상품 조회 위임")
    void findByCategoryIdsSorted_delegatesToRepository() {
        Object[] row = createNativeRow(1L, "상품A");
        when(productRepository.findByCategoryIdsFlatContent(eq(List.of(3)), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(row));
        when(productRepository.countActiveByCategoryIds(List.of(3))).thenReturn(1L);

        Page<ProductListReadModel> result = productQueryService.findByCategoryIdsSorted(List.of(3), 0, 10, "best");

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(productRepository).findByCategoryIdsFlatContent(eq(List.of(3)), any(Pageable.class));
    }

    @Test
    @DisplayName("findByCategoryIdsSorted — 복수 카테고리 ID로 상품 조회 위임")
    void findByCategoryIdsSorted_multipleIds_delegatesToRepository() {
        List<Integer> ids = List.of(1, 2, 3);
        Object[] row1 = createNativeRow(1L, "상품A");
        Object[] row2 = createNativeRow(2L, "상품B");
        when(productRepository.findByCategoryIdsFlatContent(eq(ids), any(Pageable.class)))
                .thenReturn(List.<Object[]>of(row1, row2));
        when(productRepository.countActiveByCategoryIds(ids)).thenReturn(2L);

        Page<ProductListReadModel> result = productQueryService.findByCategoryIdsSorted(ids, 0, 20, "best");

        assertThat(result.getTotalElements()).isEqualTo(2);
        verify(productRepository).findByCategoryIdsFlatContent(eq(ids), any(Pageable.class));
    }

    // [Phase 18] getBestSellers, getNewArrivals, getDeals → ProductQueryService (findBestSellersFlat 등 사용)
    @Test
    @DisplayName("getBestSellers — 베스트셀러 조회 위임")
    void getBestSellers_delegatesToRepository() {
        when(productRepository.findBestSellersFlat(any(Pageable.class))).thenReturn(Page.empty());

        productQueryService.getBestSellers(PageRequest.of(0, 8));

        verify(productRepository).findBestSellersFlat(any(Pageable.class));
    }

    @Test
    @DisplayName("getNewArrivals — 신상품 조회 위임")
    void getNewArrivals_delegatesToRepository() {
        when(productRepository.findNewArrivalsFlat(any(Pageable.class))).thenReturn(Page.empty());

        productQueryService.getNewArrivals(PageRequest.of(0, 8));

        verify(productRepository).findNewArrivalsFlat(any(Pageable.class));
    }

    @Test
    @DisplayName("getDeals — 할인 상품 조회 위임")
    void getDeals_delegatesToRepository() {
        when(productRepository.findDealsFlat(any(Pageable.class))).thenReturn(Page.empty());

        productQueryService.getDeals(PageRequest.of(0, 8));

        verify(productRepository).findDealsFlat(any(Pageable.class));
    }

    // ==================== search 정규 검색 성공 경로 ====================

    // [Phase 18] search → ProductQueryService (searchByKeywordFlat 사용)
    @Test
    @DisplayName("search — 정규 검색(tsquery) 결과가 있으면 like 폴백 없이 반환")
    void search_tsQueryHasResults_noFallback() {
        Object[] row = createNativeRow(1L, "노트북");
        Page<Object[]> tsResult = new PageImpl<>(List.<Object[]>of(row));

        when(productRepository.searchByKeywordFlat(eq("노트북"), any(Pageable.class))).thenReturn(tsResult);

        Page<ProductListReadModel> result = productQueryService.search("노트북", PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        verify(productRepository).searchByKeywordFlat(eq("노트북"), any(Pageable.class));
        verify(productRepository, never()).searchByKeywordLikeFlat(any(), any());
    }

    // ==================== findAllSorted 전체 sort 분기 ====================

    // [Phase 18/21] findAllSorted — content/count 분리 (findActiveProductsFlatContent + countActiveProducts)
    @Test
    @DisplayName("findAllSorted — price_asc → price ASC 정렬")
    void findAllSorted_priceAsc() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(0L);

        productQueryService.findAllSorted(0, 20, "price_asc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveProductsFlatContent(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("price: ASC");
    }

    @Test
    @DisplayName("findAllSorted — price_desc → price DESC 정렬")
    void findAllSorted_priceDesc() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(0L);

        productQueryService.findAllSorted(0, 20, "price_desc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveProductsFlatContent(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("price: DESC");
    }

    // [Phase 18] 네이티브 SQL용 snake_case 정렬 컬럼명 사용
    @Test
    @DisplayName("findAllSorted — newest → created_at DESC 정렬")
    void findAllSorted_newest() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(0L);

        productQueryService.findAllSorted(0, 20, "newest");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveProductsFlatContent(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("created_at: DESC");
    }

    @Test
    @DisplayName("findAllSorted — review → review_count DESC 정렬")
    void findAllSorted_review() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(0L);

        productQueryService.findAllSorted(0, 20, "review");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveProductsFlatContent(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("review_count: DESC");
    }

    @Test
    @DisplayName("findAllSorted — 알 수 없는 sort값 → default: sales_count DESC")
    void findAllSorted_defaultBest() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(0L);

        productQueryService.findAllSorted(0, 20, "best");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveProductsFlatContent(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("sales_count: DESC");
    }
}

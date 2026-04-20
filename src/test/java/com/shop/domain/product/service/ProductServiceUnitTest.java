package com.shop.domain.product.service;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.port.InventoryAdjustmentPort;
import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.repository.ProductImageRepository;
import com.shop.global.exception.ResourceNotFoundException;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceUnitTest {

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

    // [Phase 18] search, findAllSorted가 ProductQueryService로 이동됨
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

    // [Phase 18] search가 ProductQueryService로 이동됨 — 내부적으로 searchByKeywordFlat/searchByKeywordLikeFlat 호출
    @Test
    @DisplayName("search - 정규 검색 결과가 비면 like 검색으로 폴백")
    void search_fallbackToLikeWhenTsQueryIsEmpty() {
        Page<Object[]> empty = Page.empty();
        Object[] row = createNativeRow(1L, "laptop product");
        Page<Object[]> likeResult = new PageImpl<>(List.<Object[]>of(row));

        when(productRepository.searchByKeywordFlat(eq("laptop"), any(Pageable.class))).thenReturn(empty);
        when(productRepository.searchByKeywordLikeFlat(eq("laptop"), any(Pageable.class))).thenReturn(likeResult);

        Page<ProductListReadModel> result = productQueryService.search("laptop", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .as("정규 검색 결과가 없으면 like 검색 결과를 반환해야 함")
                .hasSize(1);
        verify(productRepository).searchByKeywordFlat(eq("laptop"), any(Pageable.class));
        verify(productRepository).searchByKeywordLikeFlat(eq("laptop"), any(Pageable.class));
    }

    // [Phase 18] search가 ProductQueryService로 이동됨 — searchByKeywordFlat/searchByKeywordLikeFlat 사용
    @Test
    @DisplayName("search - 검색어를 trim/소문자/공백 정규화 후 조회")
    void search_normalizesKeywordBeforeQuery() {
        when(productRepository.searchByKeywordFlat(any(String.class), any(Pageable.class))).thenReturn(Page.empty());
        when(productRepository.searchByKeywordLikeFlat(any(String.class), any(Pageable.class))).thenReturn(Page.empty());

        Pageable pageable = PageRequest.of(0, 10);
        productQueryService.search("Nike", pageable);
        productQueryService.search(" nike ", pageable);
        productQueryService.search("NIKE", pageable);

        verify(productRepository, times(3)).searchByKeywordFlat(eq("nike"), any(Pageable.class));
        verify(productRepository, times(3)).searchByKeywordLikeFlat(eq("nike"), any(Pageable.class));
    }

    // [Phase 18] search가 ProductQueryService로 이동됨 — searchByKeywordFlat/searchByKeywordLikeFlat 사용
    @Test
    @DisplayName("search - 정규 검색 쿼리 예외 발생 시 like 검색으로 폴백")
    void search_fallbackToLikeWhenTsQueryFails() {
        Object[] row = createNativeRow(1L, "키보드 상품");
        Page<Object[]> likeResult = new PageImpl<>(List.<Object[]>of(row));

        when(productRepository.searchByKeywordFlat(eq("키보드"), any(Pageable.class)))
                .thenThrow(new DataAccessResourceFailureException("fts function error"));
        when(productRepository.searchByKeywordLikeFlat(eq("키보드"), any(Pageable.class))).thenReturn(likeResult);

        Page<ProductListReadModel> result = productQueryService.search("키보드", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .as("정규 검색 쿼리 실패 시에도 like 검색 결과를 반환해야 함")
                .hasSize(1);
        verify(productRepository).searchByKeywordFlat(eq("키보드"), any(Pageable.class));
        verify(productRepository).searchByKeywordLikeFlat(eq("키보드"), any(Pageable.class));
    }

    // [Phase 18/21] findAllSorted — content/count 분리 호출 (findActiveProductsFlatContent + countActiveProducts)
    @Test
    @DisplayName("findAllSorted - sort 파라미터에 따라 정렬 필드가 선택됨")
    void findAllSorted_usesExpectedSortField() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(0L);

        productQueryService.findAllSorted(0, 12, "rating");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findActiveProductsFlatContent(pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        // [Phase 18] 네이티브 SQL용 snake_case 정렬 컬럼명 사용
        assertThat(pageable.getSort().toString())
                .as("rating 정렬은 rating_avg DESC를 사용해야 함 (네이티브 SQL snake_case)")
                .contains("rating_avg: DESC");
    }

    // [Phase 21] count 분리 캐시가 정렬/페이지 무관 단일 키 공유를 보장
    @Test
    @DisplayName("findAllSorted - 다른 sort/page 조합에서도 countActiveProducts는 한 번만 실행된다")
    void findAllSorted_sharesCountAcrossSortAndPage() {
        when(productRepository.findActiveProductsFlatContent(any(Pageable.class))).thenReturn(List.of());
        when(productRepository.countActiveProducts()).thenReturn(42L);

        Page<ProductListReadModel> p1 = productQueryService.findAllSorted(0, 12, "rating");
        Page<ProductListReadModel> p2 = productQueryService.findAllSorted(1, 12, "best");
        Page<ProductListReadModel> p3 = productQueryService.findAllSorted(2, 20, "newest");

        assertThat(p1.getTotalElements()).isEqualTo(42L);
        assertThat(p2.getTotalElements()).isEqualTo(42L);
        assertThat(p3.getTotalElements()).isEqualTo(42L);
        verify(productRepository, times(1)).countActiveProducts();
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
                java.sql.Timestamp.valueOf(LocalDateTime.now()), "/images/thumb.jpg", true
        };
    }

    @Test
    @DisplayName("findById - 존재하지 않는 상품이면 ResourceNotFoundException")
    void findById_notFound_throwsException() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.findById(999L))
                .as("없는 상품 조회 시 예외가 발생해야 함")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

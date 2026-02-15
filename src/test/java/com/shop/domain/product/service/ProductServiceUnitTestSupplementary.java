package com.shop.domain.product.service;

import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

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
    private ViewCountService viewCountService;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, viewCountService);
    }

    // ==================== 미커버 메서드 5개 ====================

    @Test
    @DisplayName("findByCategory — 카테고리 ID로 상품 조회 위임")
    void findByCategory_delegatesToRepository() {
        Page<Product> expected = new PageImpl<>(List.of(mock(Product.class)));
        when(productRepository.findByCategoryId(eq(3), any(Pageable.class))).thenReturn(expected);

        Page<Product> result = productService.findByCategory(3, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(productRepository).findByCategoryId(eq(3), any(Pageable.class));
    }

    @Test
    @DisplayName("findByCategoryIds — 복수 카테고리 ID로 상품 조회 위임")
    void findByCategoryIds_delegatesToRepository() {
        List<Integer> ids = List.of(1, 2, 3);
        Page<Product> expected = new PageImpl<>(List.of(mock(Product.class), mock(Product.class)));
        when(productRepository.findByCategoryIds(eq(ids), any(Pageable.class))).thenReturn(expected);

        Page<Product> result = productService.findByCategoryIds(ids, PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isEqualTo(2);
        verify(productRepository).findByCategoryIds(eq(ids), any(Pageable.class));
    }

    @Test
    @DisplayName("getBestSellers — 베스트셀러 조회 위임")
    void getBestSellers_delegatesToRepository() {
        when(productRepository.findBestSellers(any(Pageable.class))).thenReturn(Page.empty());

        productService.getBestSellers(PageRequest.of(0, 8));

        verify(productRepository).findBestSellers(any(Pageable.class));
    }

    @Test
    @DisplayName("getNewArrivals — 신상품 조회 위임")
    void getNewArrivals_delegatesToRepository() {
        when(productRepository.findNewArrivals(any(Pageable.class))).thenReturn(Page.empty());

        productService.getNewArrivals(PageRequest.of(0, 8));

        verify(productRepository).findNewArrivals(any(Pageable.class));
    }

    @Test
    @DisplayName("getDeals — 할인 상품 조회 위임")
    void getDeals_delegatesToRepository() {
        when(productRepository.findDeals(any(Pageable.class))).thenReturn(Page.empty());

        productService.getDeals(PageRequest.of(0, 8));

        verify(productRepository).findDeals(any(Pageable.class));
    }

    // ==================== search 정규 검색 성공 경로 ====================

    @Test
    @DisplayName("search — 정규 검색(tsquery) 결과가 있으면 like 폴백 없이 반환")
    void search_tsQueryHasResults_noFallback() {
        Product found = mock(Product.class);
        Page<Product> tsResult = new PageImpl<>(List.of(found));

        when(productRepository.searchByKeyword(eq("노트북"), any(Pageable.class))).thenReturn(tsResult);

        Page<Product> result = productService.search("노트북", PageRequest.of(0, 10));

        assertThat(result.getContent()).containsExactly(found);
        verify(productRepository).searchByKeyword(eq("노트북"), any(Pageable.class));
        verify(productRepository, never()).searchByKeywordLike(any(), any());
    }

    // ==================== findAllSorted 전체 sort 분기 ====================

    @Test
    @DisplayName("findAllSorted — price_asc → price ASC 정렬")
    void findAllSorted_priceAsc() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        productService.findAllSorted(0, 20, "price_asc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("price: ASC");
    }

    @Test
    @DisplayName("findAllSorted — price_desc → price DESC 정렬")
    void findAllSorted_priceDesc() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        productService.findAllSorted(0, 20, "price_desc");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("price: DESC");
    }

    @Test
    @DisplayName("findAllSorted — newest → createdAt DESC 정렬")
    void findAllSorted_newest() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        productService.findAllSorted(0, 20, "newest");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("createdAt: DESC");
    }

    @Test
    @DisplayName("findAllSorted — review → reviewCount DESC 정렬")
    void findAllSorted_review() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        productService.findAllSorted(0, 20, "review");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("reviewCount: DESC");
    }

    @Test
    @DisplayName("findAllSorted — 알 수 없는 sort값 → default: salesCount DESC")
    void findAllSorted_defaultBest() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        productService.findAllSorted(0, 20, "best");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().toString()).contains("salesCount: DESC");
    }
}

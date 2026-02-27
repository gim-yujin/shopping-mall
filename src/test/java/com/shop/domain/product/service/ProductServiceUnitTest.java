package com.shop.domain.product.service;

import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.domain.product.repository.ProductImageRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
    private ViewCountService viewCountService;

    @Mock
    private CategoryService categoryService;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, productImageRepository, viewCountService, categoryService);
    }

    @Test
    @DisplayName("search - 정규 검색 결과가 비면 like 검색으로 폴백")
    void search_fallbackToLikeWhenTsQueryIsEmpty() {
        Page<Product> empty = Page.empty();
        Product found = mock(Product.class);
        Page<Product> likeResult = new PageImpl<>(List.of(found));

        when(productRepository.searchByKeyword(eq("laptop"), any(Pageable.class))).thenReturn(empty);
        when(productRepository.searchByKeywordLike(eq("laptop"), any(Pageable.class))).thenReturn(likeResult);

        Page<Product> result = productService.search("laptop", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .as("정규 검색 결과가 없으면 like 검색 결과를 반환해야 함")
                .containsExactly(found);
        verify(productRepository).searchByKeyword(eq("laptop"), any(Pageable.class));
        verify(productRepository).searchByKeywordLike(eq("laptop"), any(Pageable.class));
    }

    @Test
    @DisplayName("search - 검색어를 trim/소문자/공백 정규화 후 조회")
    void search_normalizesKeywordBeforeQuery() {
        when(productRepository.searchByKeyword(any(String.class), any(Pageable.class))).thenReturn(Page.empty());
        when(productRepository.searchByKeywordLike(any(String.class), any(Pageable.class))).thenReturn(Page.empty());

        Pageable pageable = PageRequest.of(0, 10);
        productService.search("Nike", pageable);
        productService.search(" nike ", pageable);
        productService.search("NIKE", pageable);

        verify(productRepository, times(3)).searchByKeyword(eq("nike"), any(Pageable.class));
        verify(productRepository, times(3)).searchByKeywordLike(eq("nike"), any(Pageable.class));
    }

    @Test
    @DisplayName("findAllSorted - sort 파라미터에 따라 정렬 필드가 선택됨")
    void findAllSorted_usesExpectedSortField() {
        when(productRepository.findByIsActiveTrue(any(Pageable.class))).thenReturn(Page.empty());

        productService.findAllSorted(0, 12, "rating");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findByIsActiveTrue(pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getSort().toString())
                .as("rating 정렬은 ratingAvg DESC를 사용해야 함")
                .contains("ratingAvg: DESC");
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

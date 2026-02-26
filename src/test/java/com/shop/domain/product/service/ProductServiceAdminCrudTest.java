package com.shop.domain.product.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceAdminCrudTest {

    @Mock private ProductRepository productRepository;
    @Mock private ViewCountService viewCountService;
    @Mock private CategoryService categoryService;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository, viewCountService, categoryService);
    }

    private AdminProductRequest buildRequest() {
        AdminProductRequest req = new AdminProductRequest();
        req.setProductName("테스트 상품");
        req.setCategoryId(5);
        req.setDescription("설명");
        req.setPrice(new BigDecimal("29900"));
        req.setOriginalPrice(new BigDecimal("39900"));
        req.setStockQuantity(100);
        return req;
    }

    // ──────────── createProduct ────────────

    @Test
    @DisplayName("createProduct — 카테고리 조회 후 상품 생성 및 저장")
    void createProduct_savesNewProduct() {
        AdminProductRequest req = buildRequest();
        Category category = mock(Category.class);
        when(categoryService.findById(5)).thenReturn(category);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = productService.createProduct(req);

        assertThat(result.getProductName()).isEqualTo("테스트 상품");
        assertThat(result.getPrice()).isEqualByComparingTo("29900");
        assertThat(result.getOriginalPrice()).isEqualByComparingTo("39900");
        assertThat(result.getStockQuantity()).isEqualTo(100);
        assertThat(result.getIsActive()).isTrue();
        assertThat(result.getSalesCount()).isZero();
        assertThat(result.getViewCount()).isZero();

        verify(categoryService).findById(5);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("createProduct — 존재하지 않는 카테고리 시 예외")
    void createProduct_throwsWhenCategoryNotFound() {
        AdminProductRequest req = buildRequest();
        when(categoryService.findById(5)).thenThrow(new ResourceNotFoundException("카테고리", 5));

        assertThatThrownBy(() -> productService.createProduct(req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ──────────── updateProduct ────────────

    @Test
    @DisplayName("updateProduct — 기존 상품의 필드를 수정")
    void updateProduct_updatesExistingProduct() {
        AdminProductRequest req = buildRequest();
        req.setProductName("수정된 상품");
        req.setPrice(new BigDecimal("19900"));

        Category oldCat = mock(Category.class);
        Category newCat = mock(Category.class);

        Product existing = Product.create("원래 상품", oldCat, "원래 설명",
                new BigDecimal("29900"), null, 50);
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryService.findById(5)).thenReturn(newCat);

        Product result = productService.updateProduct(1L, req);

        assertThat(result.getProductName()).isEqualTo("수정된 상품");
        assertThat(result.getPrice()).isEqualByComparingTo("19900");
        assertThat(result.getCategory()).isSameAs(newCat);
        assertThat(result.getStockQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("updateProduct — 존재하지 않는 상품 시 예외")
    void updateProduct_throwsWhenProductNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.updateProduct(999L, buildRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ──────────── toggleProductActive ────────────

    @Test
    @DisplayName("toggleProductActive — 활성 → 비활성 토글")
    void toggleProductActive_togglesState() {
        Category cat = mock(Category.class);
        Product product = Product.create("상품", cat, "설명",
                new BigDecimal("10000"), null, 10);
        assertThat(product.getIsActive()).isTrue();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        productService.toggleProductActive(1L);

        assertThat(product.getIsActive()).isFalse();
    }

    @Test
    @DisplayName("toggleProductActive — 비활성 → 활성 토글")
    void toggleProductActive_reactivates() {
        Category cat = mock(Category.class);
        Product product = Product.create("상품", cat, "설명",
                new BigDecimal("10000"), null, 10);
        product.toggleActive(); // → false
        assertThat(product.getIsActive()).isFalse();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        productService.toggleProductActive(1L);

        assertThat(product.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("toggleProductActive — 존재하지 않는 상품 시 예외")
    void toggleProductActive_throwsWhenNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.toggleProductActive(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ──────────── findByIdForAdmin ────────────

    @Test
    @DisplayName("findByIdForAdmin — 카테고리 fetch join으로 상품 조회")
    void findByIdForAdmin_returnsProductWithCategory() {
        Product product = mock(Product.class);
        when(productRepository.findByIdWithCategory(1L)).thenReturn(Optional.of(product));

        Product result = productService.findByIdForAdmin(1L);

        assertThat(result).isSameAs(product);
        verify(productRepository).findByIdWithCategory(1L);
    }
}

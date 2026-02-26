package com.shop.domain.product.controller;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.order.service.OrderService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerUnitTest {

    @Mock private ProductService productService;
    @Mock private OrderService orderService;
    @Mock private CategoryService categoryService;

    @InjectMocks
    private AdminController adminController;

    // ──────────── 상품 목록 ────────────

    @Test
    @DisplayName("GET /admin/products — 상품 목록 반환")
    void adminProducts_returnsProductList() {
        when(productService.findAllForAdmin(any())).thenReturn(new PageImpl<>(List.of()));
        Model model = new ConcurrentModel();

        String view = adminController.adminProducts(0, model);

        assertThat(view).isEqualTo("admin/products");
        assertThat(model.containsAttribute("products")).isTrue();
    }

    // ──────────── 등록 폼 ────────────

    @Test
    @DisplayName("GET /admin/products/new — 빈 폼 + 카테고리 목록")
    void newProductForm_returnsFormWithCategories() {
        when(categoryService.getAllActiveCategories()).thenReturn(List.of());
        Model model = new ConcurrentModel();

        String view = adminController.newProductForm(model);

        assertThat(view).isEqualTo("admin/product-form");
        assertThat(model.getAttribute("editMode")).isEqualTo(false);
        assertThat(model.containsAttribute("request")).isTrue();
        assertThat(model.containsAttribute("categories")).isTrue();
    }

    // ──────────── 등록 처리 ────────────

    @Test
    @DisplayName("POST /admin/products — 유효성 통과 시 등록 후 리다이렉트")
    void createProduct_successRedirects() {
        AdminProductRequest request = new AdminProductRequest();
        request.setProductName("새 상품");
        request.setCategoryId(1);
        request.setPrice(new BigDecimal("10000"));
        request.setStockQuantity(50);

        BindingResult bindingResult = new BeanPropertyBindingResult(request, "request");
        Category cat = mock(Category.class);
        Product product = Product.create("새 상품", cat, null, new BigDecimal("10000"), null, 50);
        when(productService.createProduct(request)).thenReturn(product);

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        Model model = new ConcurrentModel();

        String view = adminController.createProduct(request, bindingResult, model, redirect);

        assertThat(view).isEqualTo("redirect:/admin/products");
        assertThat(redirect.getFlashAttributes()).containsKey("successMessage");
        verify(productService).createProduct(request);
    }

    @Test
    @DisplayName("POST /admin/products — 유효성 실패 시 폼 재표시")
    void createProduct_validationErrorReturnsForm() {
        AdminProductRequest request = new AdminProductRequest();
        BindingResult bindingResult = new BeanPropertyBindingResult(request, "request");
        bindingResult.rejectValue("productName", "NotBlank", "상품명을 입력해주세요.");

        when(categoryService.getAllActiveCategories()).thenReturn(List.of());
        Model model = new ConcurrentModel();
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = adminController.createProduct(request, bindingResult, model, redirect);

        assertThat(view).isEqualTo("admin/product-form");
        assertThat(model.getAttribute("editMode")).isEqualTo(false);
        verify(productService, never()).createProduct(any());
    }

    // ──────────── 수정 폼 ────────────

    @Test
    @DisplayName("GET /admin/products/{id}/edit — 기존 상품 데이터로 폼 채움")
    void editProductForm_populatesForm() {
        Category cat = mock(Category.class);
        when(cat.getCategoryId()).thenReturn(3);
        Product product = Product.create("기존 상품", cat, "설명",
                new BigDecimal("15000"), new BigDecimal("20000"), 30);
        when(productService.findByIdForAdmin(1L)).thenReturn(product);
        when(categoryService.getAllActiveCategories()).thenReturn(List.of());
        Model model = new ConcurrentModel();

        String view = adminController.editProductForm(1L, model);

        assertThat(view).isEqualTo("admin/product-form");
        assertThat(model.getAttribute("editMode")).isEqualTo(true);
        assertThat(model.getAttribute("productId")).isEqualTo(1L);

        AdminProductRequest req = (AdminProductRequest) model.getAttribute("request");
        assertThat(req).isNotNull();
        assertThat(req.getProductName()).isEqualTo("기존 상품");
        assertThat(req.getCategoryId()).isEqualTo(3);
        assertThat(req.getPrice()).isEqualByComparingTo("15000");
    }

    // ──────────── 수정 처리 ────────────

    @Test
    @DisplayName("POST /admin/products/{id} — 수정 성공 시 리다이렉트")
    void updateProduct_successRedirects() {
        AdminProductRequest request = new AdminProductRequest();
        request.setProductName("수정 상품");
        request.setCategoryId(1);
        request.setPrice(new BigDecimal("10000"));
        request.setStockQuantity(50);

        BindingResult bindingResult = new BeanPropertyBindingResult(request, "request");
        Category cat = mock(Category.class);
        Product product = Product.create("수정 상품", cat, null, new BigDecimal("10000"), null, 50);
        when(productService.updateProduct(eq(1L), eq(request))).thenReturn(product);

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        Model model = new ConcurrentModel();

        String view = adminController.updateProduct(1L, request, bindingResult, model, redirect);

        assertThat(view).isEqualTo("redirect:/admin/products");
        verify(productService).updateProduct(1L, request);
    }

    // ──────────── 토글 ────────────

    @Test
    @DisplayName("POST /admin/products/{id}/toggle-active — 상태 토글 후 리다이렉트")
    void toggleProductActive_redirects() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = adminController.toggleProductActive(1L, redirect);

        assertThat(view).isEqualTo("redirect:/admin/products");
        verify(productService).toggleProductActive(1L);
        assertThat(redirect.getFlashAttributes()).containsKey("successMessage");
    }
}

package com.shop.domain.product.service;

import com.shop.domain.category.entity.Category;
import com.shop.domain.category.service.CategoryService;
import com.shop.domain.inventory.service.InventoryService;
import com.shop.domain.product.dto.AdminProductRequest;
import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.entity.Product;
import com.shop.domain.product.entity.ProductImage;
import com.shop.domain.product.repository.ProductImageRepository;
import com.shop.domain.product.repository.ProductRepository;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProductService Branch 커버리지 보강 테스트.
 *
 * 기존 테스트(UnitTest, Supplementary, AdminCrudTest)에서 누락된 분기를 집중 커버한다.
 * JaCoCo 기준 product.service 패키지의 Branch가 46%에서 70%+ 이상으로 올라가는 것이 목표.
 *
 * 주요 미커버 분기:
 * - normalizeSearchKeyword(null) → "" 반환
 * - updateProduct: stockDelta==0 (adjustStock 미호출), imageUrls!=null (이미지 교체)
 * - saveProductImages: 빈 리스트, null URL, blank URL, 썸네일(i==0) vs 일반(i>0)
 * - createProduct: imageUrls 포함 요청
 * - findByIdCached: 정상 조회 / not found
 * - findByIdAndIncrementView: 정상 조회 / not found
 * - findByIdForAdmin: not found
 * - getProductImages: 위임 확인
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceBranchCoverageTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private ViewCountService viewCountService;
    @Mock private CategoryService categoryService;
    @Mock private InventoryService inventoryService;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(
                productRepository, productImageRepository,
                viewCountService, categoryService, inventoryService
        );
    }

    private AdminProductRequest buildRequest(int stockQuantity, List<String> imageUrls) {
        AdminProductRequest req = new AdminProductRequest();
        req.setProductName("테스트 상품");
        req.setCategoryId(1);
        req.setDescription("설명");
        req.setPrice(new BigDecimal("10000"));
        req.setOriginalPrice(new BigDecimal("12000"));
        req.setStockQuantity(stockQuantity);
        req.setImageUrls(imageUrls);
        return req;
    }

    // =====================================================
    // 1. normalizeSearchKeyword — null 분기
    // =====================================================

    @Test
    @DisplayName("normalizeSearchKeyword(null) → 빈 문자열 반환")
    void normalizeSearchKeyword_null_returnsEmpty() {
        // given: keyword가 null일 때 NPE 대신 빈 문자열로 정규화해야 한다.
        // 이 방어 로직이 없으면 search() 메서드 내에서 trim() 호출 시 NPE 발생.
        String result = productService.normalizeSearchKeyword(null);

        assertThat(result).isEmpty();
    }

    // =====================================================
    // 2. findByIdCached — 캐시 조회 성공/실패 분기
    // =====================================================

    @Nested
    @DisplayName("findByIdCached")
    class FindByIdCached {

        @Test
        @DisplayName("정상 조회 → CachedProductDetail 반환")
        void findByIdCached_success_returnsCachedDetail() {
            // given: findByIdWithCategory가 Product를 반환하면
            // CachedProductDetail.from()으로 변환하여 캐시에 저장한다.
            // 단위 테스트에서는 @Cacheable이 동작하지 않으므로 매번 메서드가 실행된다.
            Category category = mock(Category.class);
            lenient().when(category.getCategoryId()).thenReturn(1);
            lenient().when(category.getCategoryName()).thenReturn("전자기기");

            Product product = Product.create("노트북", category, "설명",
                    new BigDecimal("1500000"), new BigDecimal("1800000"), 10);
            ReflectionTestUtils.setField(product, "productId", 1L);

            when(productRepository.findByIdWithCategory(1L)).thenReturn(Optional.of(product));

            // when
            CachedProductDetail result = productService.findByIdCached(1L);

            // then: Product 엔티티가 아닌 불변 DTO가 반환됨
            assertThat(result.productId()).isEqualTo(1L);
            assertThat(result.productName()).isEqualTo("노트북");
            assertThat(result.categoryName()).isEqualTo("전자기기");
        }

        @Test
        @DisplayName("존재하지 않는 상품 → ResourceNotFoundException")
        void findByIdCached_notFound_throwsException() {
            // given
            when(productRepository.findByIdWithCategory(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.findByIdCached(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =====================================================
    // 3. findByIdAndIncrementView — deprecated 메서드 분기
    // =====================================================

    @Nested
    @DisplayName("findByIdAndIncrementView (deprecated)")
    class FindByIdAndIncrementView {

        @Test
        @DisplayName("정상 조회 → 상품 반환 + 비동기 조회수 증가 호출")
        void findByIdAndIncrementView_success() {
            // given: deprecated 메서드지만 기존 호출처 호환을 위해 유지 중.
            // findByIdCached + ViewCountService 조합으로 대체 권장.
            Product product = mock(Product.class);
            when(productRepository.findByIdWithCategory(1L)).thenReturn(Optional.of(product));

            // when
            Product result = productService.findByIdAndIncrementView(1L);

            // then
            assertThat(result).isSameAs(product);
            verify(viewCountService).incrementAsync(1L);
        }

        @Test
        @DisplayName("존재하지 않는 상품 → ResourceNotFoundException, 조회수 증가 미호출")
        void findByIdAndIncrementView_notFound_throwsException() {
            // given
            when(productRepository.findByIdWithCategory(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> productService.findByIdAndIncrementView(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(viewCountService, never()).incrementAsync(anyLong());
        }
    }

    // =====================================================
    // 4. findByIdForAdmin — not found 분기
    // =====================================================

    @Test
    @DisplayName("findByIdForAdmin — 존재하지 않는 상품 시 예외")
    void findByIdForAdmin_notFound_throwsException() {
        // given
        when(productRepository.findByIdWithCategory(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.findByIdForAdmin(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // =====================================================
    // 5. updateProduct — stockDelta==0 분기
    // =====================================================

    @Test
    @DisplayName("updateProduct — 재고 변경 없으면 adjustStock 미호출")
    void updateProduct_noStockChange_skipsAdjustStock() {
        // given: 기존 재고 100, 요청 재고 100 → delta=0
        // 기존 테스트(AdminCrudTest)는 delta!=0만 커버했으므로 이 분기가 누락됨.
        Category category = mock(Category.class);
        Product existing = Product.create("상품", category, "설명",
                new BigDecimal("10000"), null, 100);
        ReflectionTestUtils.setField(existing, "productId", 1L);

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryService.findById(1)).thenReturn(category);

        // stockQuantity=100 (기존과 동일), imageUrls=null
        AdminProductRequest req = buildRequest(100, null);

        // when
        productService.updateProduct(1L, req);

        // then: 재고 변경 없으므로 InventoryService가 호출되지 않아야 함
        verify(inventoryService, never()).adjustStock(anyLong(), anyInt(), anyString(), any());
    }

    // =====================================================
    // 6. updateProduct — imageUrls 분기
    // =====================================================

    @Test
    @DisplayName("updateProduct — imageUrls가 있으면 기존 이미지 삭제 후 새 이미지 저장")
    void updateProduct_withImageUrls_replacesImages() {
        // given: imageUrls가 null이 아니면 전량 교체(delete all + save new)
        // 기존 테스트에서는 imageUrls=null(기본값)만 커버되어 이 분기가 누락됨.
        Category category = mock(Category.class);
        Product existing = Product.create("상품", category, "설명",
                new BigDecimal("10000"), null, 50);
        ReflectionTestUtils.setField(existing, "productId", 1L);

        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryService.findById(1)).thenReturn(category);

        // stockQuantity=50 (변경 없음), imageUrls 포함
        AdminProductRequest req = buildRequest(50, List.of("https://img.com/a.jpg", "https://img.com/b.jpg"));

        // when
        productService.updateProduct(1L, req);

        // then: 기존 이미지 삭제 + 새 이미지 2개 저장
        verify(productImageRepository).deleteByProduct_ProductId(1L);
        verify(productImageRepository, times(2)).save(any(ProductImage.class));
    }

    // =====================================================
    // 7. createProduct — imageUrls 포함 분기
    // =====================================================

    @Test
    @DisplayName("createProduct — imageUrls가 포함되면 이미지 엔티티가 저장됨")
    void createProduct_withImageUrls_savesImages() {
        // given: 기존 createProduct 테스트는 imageUrls 없이만 테스트함.
        // 이미지가 포함된 경우 saveProductImages()의 내부 루프가 실행되는지 검증.
        Category category = mock(Category.class);
        when(categoryService.findById(1)).thenReturn(category);
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "productId", 1L);
            return p;
        });

        AdminProductRequest req = buildRequest(10, List.of("https://img.com/thumb.jpg", "https://img.com/detail.jpg"));

        // when
        productService.createProduct(req);

        // then: 이미지 2개 저장 (첫 번째는 썸네일)
        ArgumentCaptor<ProductImage> captor = ArgumentCaptor.forClass(ProductImage.class);
        verify(productImageRepository, times(2)).save(captor.capture());

        List<ProductImage> saved = captor.getAllValues();
        // 첫 번째 이미지: isThumbnail=true, imageOrder=0
        assertThat(saved.get(0).getIsThumbnail()).isTrue();
        assertThat(saved.get(0).getImageOrder()).isEqualTo(0);
        // 두 번째 이미지: isThumbnail=false, imageOrder=1
        assertThat(saved.get(1).getIsThumbnail()).isFalse();
        assertThat(saved.get(1).getImageOrder()).isEqualTo(1);
    }

    // =====================================================
    // 8. saveProductImages — 방어 분기 (빈 리스트, null/blank URL)
    // =====================================================

    @Nested
    @DisplayName("saveProductImages 방어 분기")
    class SaveProductImagesDefensive {

        @Test
        @DisplayName("imageUrls가 빈 리스트면 이미지 저장 스킵")
        void createProduct_emptyImageUrls_skipsImageSave() {
            // given: 빈 리스트는 null과 동일하게 조기 반환.
            // saveProductImages()의 isEmpty() 분기를 커버한다.
            Category category = mock(Category.class);
            when(categoryService.findById(1)).thenReturn(category);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminProductRequest req = buildRequest(10, Collections.emptyList());

            // when
            productService.createProduct(req);

            // then: ProductImageRepository.save()가 호출되지 않음
            verify(productImageRepository, never()).save(any(ProductImage.class));
        }

        @Test
        @DisplayName("imageUrls에 null/blank 항목이 섞여 있으면 해당 항목만 스킵")
        void createProduct_nullAndBlankUrls_skipsInvalidOnly() {
            // given: [정상URL, null, "  ", 정상URL] → 유효한 2개만 저장
            // saveProductImages()의 url==null, url.isBlank() continue 분기를 커버한다.
            Category category = mock(Category.class);
            when(categoryService.findById(1)).thenReturn(category);
            when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
                Product p = inv.getArgument(0);
                ReflectionTestUtils.setField(p, "productId", 1L);
                return p;
            });

            // Arrays.asList를 사용해 null 요소 허용
            List<String> urls = Arrays.asList("https://img.com/a.jpg", null, "   ", "https://img.com/b.jpg");
            AdminProductRequest req = buildRequest(10, urls);

            // when
            productService.createProduct(req);

            // then: 유효한 URL 2개만 저장됨 (null과 blank는 스킵)
            verify(productImageRepository, times(2)).save(any(ProductImage.class));
        }
    }

    // =====================================================
    // 9. getProductImages — 위임 확인
    // =====================================================

    @Test
    @DisplayName("getProductImages — ProductImageRepository에 위임")
    void getProductImages_delegatesToRepository() {
        // given
        List<ProductImage> images = List.of(mock(ProductImage.class));
        when(productImageRepository.findByProduct_ProductIdOrderByImageOrderAsc(1L))
                .thenReturn(images);

        // when
        List<ProductImage> result = productService.getProductImages(1L);

        // then
        assertThat(result).isSameAs(images);
        verify(productImageRepository).findByProduct_ProductIdOrderByImageOrderAsc(1L);
    }
}

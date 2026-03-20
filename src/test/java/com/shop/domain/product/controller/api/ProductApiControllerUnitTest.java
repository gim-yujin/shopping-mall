package com.shop.domain.product.controller.api;

import com.shop.domain.product.dto.CachedProductDetail;
import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.product.service.ViewCountService;
import com.shop.global.backpressure.BackpressureDetector;
import com.shop.global.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ProductApiController 단위 테스트.
 *
 * 상품 REST API의 2개 엔드포인트(목록 조회, 상세 조회)를 검증한다.
 * 인증 불필요 경로이므로 SecurityContextHolder 설정 없이 테스트한다.
 *
 * 기존 SSR ProductController에는 테스트가 있었으나,
 * REST API 컨트롤러는 테스트가 없어 API 계층 커버리지에 공백이 있었다.
 */
@ExtendWith(MockitoExtension.class)
class ProductApiControllerUnitTest {

    // [Phase 18] ProductService → ProductQueryService: CQRS 읽기 경로 분리에 따라 읽기 모의 객체 변경
    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private ViewCountService viewCountService;

    @Mock
    private BackpressureDetector backpressureDetector;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProductApiController controller = new ProductApiController(productQueryService, viewCountService, backpressureDetector);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * CachedProductDetail 픽스처를 생성한다.
     * ProductDetailResponse.from(cached)이 내부 필드를 참조하므로 실제 record 인스턴스를 사용한다.
     */
    private CachedProductDetail createCachedProduct(Long productId) {
        return new CachedProductDetail(
                productId, "테스트 상품", "설명",
                new BigDecimal("10000"), new BigDecimal("12000"), 16,
                50, 100, 500, new BigDecimal("4.50"), 25,
                true, "/images/thumb.jpg", 1, "전자기기",
                LocalDateTime.now()
        );
    }

    /**
     * [Phase 18] ProductListReadModel 픽스처. findAllSorted가 Page<ProductListReadModel>을 반환하므로
     * Product 엔티티 Mock 대신 불변 record를 직접 생성한다.
     */
    private ProductListReadModel createReadModel(Long productId, String name) {
        return new ProductListReadModel(
                productId, name,
                new BigDecimal("10000"), new BigDecimal("12000"), 16,
                new BigDecimal("4.50"), 25, 100,
                "/images/thumb.jpg", 1, "전자기기",
                LocalDateTime.now(), true
        );
    }

    // ── GET /api/v1/products — 상품 목록 조회 ──────────────

    @Test
    @DisplayName("GET /api/v1/products — 기본 파라미터로 목록 조회 성공")
    void listProducts_defaultParams_returnsPagedResponse() throws Exception {
        // [Phase 18] Page<Product> → Page<ProductListReadModel>: CQRS 읽기 모델 전환
        ProductListReadModel p1 = createReadModel(1L, "상품A");
        ProductListReadModel p2 = createReadModel(2L, "상품B");
        Page<ProductListReadModel> page = new PageImpl<>(List.of(p1, p2));

        when(productQueryService.findAllSorted(0, 20, "best")).thenReturn(page);

        // when & then: 기본값 page=0, size=20, sort=best
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].productId", is(1)))
                .andExpect(jsonPath("$.data.content[1].productId", is(2)));

        verify(productQueryService).findAllSorted(0, 20, "best");
    }

    @Test
    @DisplayName("GET /api/v1/products?sort=price_asc — 정렬 파라미터 전달 확인")
    void listProducts_sortParam_passedToService() throws Exception {
        // [Phase 18] Page<Product> → Page<ProductListReadModel>: CQRS 읽기 모델 전환
        Page<ProductListReadModel> emptyPage = new PageImpl<>(List.of());
        when(productQueryService.findAllSorted(anyInt(), anyInt(), eq("price_asc"))).thenReturn(emptyPage);

        // when & then
        mockMvc.perform(get("/api/v1/products")
                        .param("page", "1")
                        .param("size", "10")
                        .param("sort", "price_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        // PagingParams.normalizeProductSort("price_asc") → "price_asc" (유효한 값)
        verify(productQueryService).findAllSorted(1, 10, "price_asc");
    }

    // ── GET /api/v1/products/{productId} — 상품 상세 조회 ──

    @Test
    @DisplayName("GET /api/v1/products/{id} — 상세 조회 성공 + 조회수 증가 호출")
    void getProduct_success_incrementsViewCount() throws Exception {
        // given: 캐시된 상품 상세 정보 반환
        CachedProductDetail cached = createCachedProduct(10L);
        when(productQueryService.findByIdCached(10L)).thenReturn(cached);

        // when & then
        mockMvc.perform(get("/api/v1/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.productId", is(10)))
                .andExpect(jsonPath("$.data.productName", is("테스트 상품")))
                .andExpect(jsonPath("$.data.inStock", is(true)));

        // [P0 FIX] 조회수 증가가 캐시 메서드 밖에서 매번 호출되는지 검증
        verify(viewCountService).incrementAsync(10L);
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — 존재하지 않는 상품 시 예외 전파")
    void getProduct_notFound_throwsException() throws Exception {
        // given: 상품이 존재하지 않음
        when(productQueryService.findByIdCached(999L))
                .thenThrow(new ResourceNotFoundException("상품", 999L));

        // when & then: standaloneSetup에는 GlobalExceptionHandler가 등록되어 있지 않으므로
        // ResourceNotFoundException이 ServletException으로 래핑되어 전파된다.
        // 실제 운영에서는 @ControllerAdvice가 이를 잡아 적절한 HTTP 상태를 반환한다.
        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/products/999")))
                .hasCauseInstanceOf(ResourceNotFoundException.class);

        // 조회수 증가가 호출되지 않아야 함 (상품 조회 실패 시 증가 무의미)
        verify(viewCountService, never()).incrementAsync(anyLong());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — 백프레셔 CRITICAL 시 조회수 증가를 건너뛴다")
    void getProduct_criticalBackpressure_skipsViewCount() throws Exception {
        // 백프레셔 CRITICAL 상태: shouldShedNonCritical()이 true → 조회수 증가 건너뜀
        // 조회수는 비필수 지표이므로 시스템 과부하 시 큐에 작업을 추가하지 않아
        // 핵심 요청(상품 상세 조회 자체)의 처리를 보호한다.
        CachedProductDetail cached = createCachedProduct(10L);
        when(productQueryService.findByIdCached(10L)).thenReturn(cached);
        when(backpressureDetector.shouldShedNonCritical()).thenReturn(true);

        mockMvc.perform(get("/api/v1/products/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        // CRITICAL 상태이므로 조회수 증가가 호출되지 않아야 함
        verify(viewCountService, never()).incrementAsync(anyLong());
    }

    @Test
    @DisplayName("GET /api/v1/products/{id} — 재고 0인 상품은 inStock=false")
    void getProduct_outOfStock_inStockFalse() throws Exception {
        // given: 재고가 0인 상품
        CachedProductDetail outOfStock = new CachedProductDetail(
                20L, "품절 상품", "설명",
                new BigDecimal("5000"), new BigDecimal("7000"), 28,
                0, 200, 1000, new BigDecimal("3.80"), 50,
                true, "/images/sold-out.jpg", 2, "의류",
                LocalDateTime.now()
        );
        when(productQueryService.findByIdCached(20L)).thenReturn(outOfStock);

        // when & then: ProductDetailResponse.from()에서 stockQuantity=0 → inStock=false
        mockMvc.perform(get("/api/v1/products/20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inStock", is(false)));
    }
}

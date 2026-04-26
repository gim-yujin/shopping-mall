package com.shop.domain.search.controller.api;

import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SearchApiController 단위 테스트.
 *
 * <p>검색 REST API의 2개 엔드포인트를 검증한다:
 * GET /api/v1/search(상품 검색), GET /api/v1/search/popular(인기 검색어).
 * 인증 불필요 경로이므로 기본적으로 SecurityContext 없이 테스트하며,
 * 인증된 사용자의 검색 로그 기록도 별도로 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchApiControllerUnitTest {

    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private SearchService searchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SearchApiController controller = new SearchApiController(productQueryService, searchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ProductListReadModel createReadModel(Long productId, String name) {
        return new ProductListReadModel(
                productId, name,
                new BigDecimal("15000"), new BigDecimal("20000"), 25,
                new BigDecimal("4.20"), 10, 50,
                "/images/product" + productId + ".jpg", 1, "전자기기",
                LocalDateTime.now(), true, 50
        );
    }

    // ── GET /api/v1/search — 상품 검색 ─────────────────────────

    @Nested
    @DisplayName("GET /api/v1/search — 상품 검색")
    class SearchTests {

        @Test
        @DisplayName("검색 결과가 있으면 페이징된 목록을 반환한다")
        void search_withResults_returnsPagedResponse() throws Exception {
            ProductListReadModel p1 = createReadModel(1L, "노트북");
            ProductListReadModel p2 = createReadModel(2L, "노트북 파우치");
            Page<ProductListReadModel> page = new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 20), 2);

            when(productQueryService.search(eq("노트북"), any(PageRequest.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/search").param("q", "노트북"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content.length()").value(2))
                    .andExpect(jsonPath("$.data.content[0].productId").value(1))
                    .andExpect(jsonPath("$.data.content[1].productId").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(2));
        }

        @Test
        @DisplayName("검색 결과가 없으면 빈 목록을 반환한다")
        void search_noResults_returnsEmptyPage() throws Exception {
            Page<ProductListReadModel> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 20), 0);

            when(productQueryService.search(eq("없는상품"), any(PageRequest.class))).thenReturn(emptyPage);

            mockMvc.perform(get("/api/v1/search").param("q", "없는상품"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.content").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @Test
        @DisplayName("첫 페이지 조회 시 검색 로그를 기록한다")
        void search_firstPage_logsSearch() throws Exception {
            Page<ProductListReadModel> page = new PageImpl<>(
                    List.of(createReadModel(1L, "키보드")), PageRequest.of(0, 20), 1);

            when(productQueryService.search(eq("키보드"), any(PageRequest.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/search")
                    .param("q", "키보드")
                    .param("page", "0"));

            verify(searchService).logSearch(isNull(), eq("키보드"), eq(1), anyString(), any());
        }

        @Test
        @DisplayName("두 번째 이후 페이지 조회 시 검색 로그를 기록하지 않는다")
        void search_secondPage_doesNotLogSearch() throws Exception {
            Page<ProductListReadModel> page = new PageImpl<>(
                    List.of(createReadModel(3L, "마우스")), PageRequest.of(1, 20), 30);

            when(productQueryService.search(eq("마우스"), any(PageRequest.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/search")
                    .param("q", "마우스")
                    .param("page", "1"));

            verify(searchService, never()).logSearch(any(), anyString(), anyInt(), anyString(), any());
        }

        @Test
        @DisplayName("인증된 사용자의 검색 시 userId가 로그에 포함된다")
        void search_authenticatedUser_logsWithUserId() throws Exception {
            CustomUserPrincipal principal = new CustomUserPrincipal(
                    42L, "tester", "encoded", "테스터", "ROLE_USER",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
            );

            Page<ProductListReadModel> page = new PageImpl<>(
                    List.of(createReadModel(1L, "모니터")), PageRequest.of(0, 20), 1);
            when(productQueryService.search(eq("모니터"), any(PageRequest.class))).thenReturn(page);

            mockMvc.perform(get("/api/v1/search")
                    .param("q", "모니터")
                    .param("page", "0"));

            verify(searchService).logSearch(eq(42L), eq("모니터"), eq(1), anyString(), any());
        }

        @Test
        @DisplayName("페이지/사이즈 파라미터가 정규화된다")
        void search_negativePageParam_normalizedToZero() throws Exception {
            Page<ProductListReadModel> page = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 20), 0);
            when(productQueryService.search(eq("테스트"), eq(PageRequest.of(0, 20)))).thenReturn(page);

            mockMvc.perform(get("/api/v1/search")
                            .param("q", "테스트")
                            .param("page", "-1")
                            .param("size", "0"))
                    .andExpect(status().isOk());

            verify(productQueryService).search(eq("테스트"), eq(PageRequest.of(0, 20)));
        }
    }

    // ── GET /api/v1/search/popular — 인기 검색어 ───────────────

    @Nested
    @DisplayName("GET /api/v1/search/popular — 인기 검색어 조회")
    class PopularKeywordsTests {

        @Test
        @DisplayName("인기 검색어 목록을 반환한다")
        void getPopularKeywords_returnsList() throws Exception {
            when(searchService.getPopularKeywords())
                    .thenReturn(List.of("노트북", "키보드", "마우스"));

            mockMvc.perform(get("/api/v1/search/popular"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andExpect(jsonPath("$.data[0]").value("노트북"))
                    .andExpect(jsonPath("$.data[1]").value("키보드"))
                    .andExpect(jsonPath("$.data[2]").value("마우스"));
        }

        @Test
        @DisplayName("인기 검색어가 없으면 빈 목록을 반환한다")
        void getPopularKeywords_empty_returnsEmptyList() throws Exception {
            when(searchService.getPopularKeywords()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/v1/search/popular"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }
}

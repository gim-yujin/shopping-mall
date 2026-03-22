package com.shop.domain.search.controller;

import com.shop.domain.product.dto.ProductListReadModel;
import com.shop.domain.product.service.ProductQueryService;
import com.shop.domain.search.service.SearchService;
import com.shop.global.backpressure.BackpressureDetector;
import com.shop.global.security.ClientIpResolver;
import com.shop.global.security.CustomUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * SearchController 분기 커버리지 보강 테스트.
 *
 * <p>기존 SearchControllerTest에서 다루지 않은 분기를 검증한다:
 * - keyword가 null인 경우 → 빈 문자열로 정규화
 * - keyword가 빈 문자열인 경우 → 인기 검색어만 반환
 * - page != 0인 경우 → 검색 로그 기록 건너뛰기
 * - backpressureDetector.shouldShedNonCritical() == true → 로그 건너뛰기
 * - 비인증 사용자 → userId null로 검색 로그 기록</p>
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerBranchTest {

    @Mock
    private ProductQueryService productQueryService;
    @Mock
    private SearchService searchService;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private BackpressureDetector backpressureDetector;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SearchController controller = new SearchController(
                productQueryService, searchService, clientIpResolver, backpressureDetector);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── keyword null → 빈 문자열 정규화, 인기 검색어 반환 ──

    @Test
    @DisplayName("keyword가 빈 문자열이면 인기 검색어만 반환하고 검색 수행하지 않음")
    void emptyKeyword_returnsPopularKeywords() throws Exception {
        // given: 인기 검색어 mock
        when(searchService.getPopularKeywords()).thenReturn(List.of("인기1", "인기2"));

        // when & then: 빈 키워드 → 인기 검색어만 표시
        mockMvc.perform(get("/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("product/search"))
                .andExpect(model().attributeExists("popularKeywords"))
                .andExpect(model().attributeDoesNotExist("products"));

        // 검색 서비스 호출되지 않음
        verify(productQueryService, never()).search(anyString(), any());
    }

    // ── page != 0 → 검색 로그 건너뛰기 ──

    @Test
    @DisplayName("page != 0이면 검색 로그를 기록하지 않는다 (페이지네이션 시 중복 방지)")
    void nonFirstPage_skipsSearchLog() throws Exception {
        // given
        Page<ProductListReadModel> results = new PageImpl<>(Collections.emptyList());
        when(productQueryService.search(eq("테스트"), any(PageRequest.class))).thenReturn(results);

        // when: page=1 → 첫 페이지가 아니므로 로그 건너뛰기
        mockMvc.perform(get("/search").param("q", "테스트").param("page", "1"))
                .andExpect(status().isOk());

        // then: logSearch 호출되지 않음
        verify(searchService, never()).logSearch(any(), anyString(), any(int.class),
                anyString(), anyString());
    }

    // ── backpressure → 검색 로그 건너뛰기 ──

    @Test
    @DisplayName("backpressure 과부하 시 검색 로그를 건너뛴다")
    void backpressure_skipsSearchLog() throws Exception {
        // given: 과부하 상태
        Page<ProductListReadModel> results = new PageImpl<>(Collections.emptyList());
        when(productQueryService.search(eq("테스트"), any(PageRequest.class))).thenReturn(results);
        when(backpressureDetector.shouldShedNonCritical()).thenReturn(true);

        // when
        mockMvc.perform(get("/search").param("q", "테스트").param("page", "0"))
                .andExpect(status().isOk());

        // then: 과부하로 로그 건너뜀
        verify(searchService, never()).logSearch(any(), anyString(), any(int.class),
                anyString(), anyString());
    }

    // ── 비인증 사용자 → userId null ──

    @Test
    @DisplayName("비인증 사용자의 검색 → userId null로 로그 기록")
    void anonymousUser_logsWithNullUserId() throws Exception {
        // given: 인증 컨텍스트 비어있음
        Page<ProductListReadModel> results = new PageImpl<>(Collections.emptyList());
        when(productQueryService.search(eq("노트북"), any(PageRequest.class))).thenReturn(results);
        when(backpressureDetector.shouldShedNonCritical()).thenReturn(false);
        when(clientIpResolver.resolveClientIp(any())).thenReturn("192.168.1.1");

        // when
        mockMvc.perform(get("/search").param("q", "노트북").param("page", "0"))
                .andExpect(status().isOk());

        // then: userId = null 로 로그 기록
        verify(searchService).logSearch(isNull(), eq("노트북"), any(int.class),
                eq("192.168.1.1"), any());
    }

    // ── 인증 사용자 → userId 포함 ──

    @Test
    @DisplayName("인증 사용자의 검색 → userId 포함하여 로그 기록")
    void authenticatedUser_logsWithUserId() throws Exception {
        // given: 인증된 사용자
        CustomUserPrincipal principal = new CustomUserPrincipal(
                5L, "tester", "encoded", "테스터", "ROLE_USER",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        Page<ProductListReadModel> results = new PageImpl<>(Collections.emptyList());
        when(productQueryService.search(eq("키보드"), any(PageRequest.class))).thenReturn(results);
        when(backpressureDetector.shouldShedNonCritical()).thenReturn(false);
        when(clientIpResolver.resolveClientIp(any())).thenReturn("10.0.0.1");

        // when
        mockMvc.perform(get("/search").param("q", "키보드").param("page", "0"))
                .andExpect(status().isOk());

        // then: userId=5로 로그 기록
        verify(searchService).logSearch(eq(5L), eq("키보드"), any(int.class),
                eq("10.0.0.1"), any());
    }
}

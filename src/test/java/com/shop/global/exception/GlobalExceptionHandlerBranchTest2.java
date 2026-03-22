package com.shop.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 추가 분기 커버리지 테스트.
 *
 * <p>기존 GlobalExceptionHandlerBranchTest에서 다루지 않은 분기를 검증한다:
 * - isTrustedRefererHost: Host 헤더에 포트 번호가 포함된 경우
 * - isAjaxRequest: Accept 헤더에 application/json 포함 시 AJAX 판별
 * - isAjaxRequest: X-Requested-With 헤더 판별
 * - appendAllowedQueryParams: 허용/비허용 파라미터 혼재 시 필터링</p>
 */
class GlobalExceptionHandlerBranchTest2 {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── Host 헤더에 포트 번호 포함 ──

    @Test
    @DisplayName("Host 헤더에 포트 번호 포함 → 포트 제거 후 호스트 비교 → 리다이렉트 허용")
    void refererHost_withPort_matchesAfterPortRemoval() {
        // given: Host = "localhost:8080", Referer host = "localhost"
        BusinessException ex = new BusinessException("TEST", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "localhost:8080");
        request.addHeader("Referer", "http://localhost/cart");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        // when
        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        // then: /cart로 리다이렉트 (포트 제거 후 localhost == localhost 일치)
        assertThat(result).isEqualTo("redirect:/cart");
    }

    // ── AJAX 감지: Accept 헤더 ──

    @Test
    @DisplayName("Accept: application/json → AJAX 감지 → ResponseEntity 반환")
    void ajaxRequest_acceptHeader_returnsResponseEntity() {
        BusinessException ex = new BusinessException("TEST", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "application/json");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        // AJAX 감지 → ResponseEntity 반환 (String이 아닌 객체)
        assertThat(result).isNotInstanceOf(String.class);
    }

    @Test
    @DisplayName("X-Requested-With: XMLHttpRequest → AJAX 감지 → ResponseEntity 반환")
    void ajaxRequest_xRequestedWith_returnsResponseEntity() {
        BusinessException ex = new BusinessException("TEST", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isNotInstanceOf(String.class);
    }

    // ── 허용된 쿼리 파라미터 필터링 ──

    @Test
    @DisplayName("Referer에 허용/비허용 파라미터 혼재 → 허용된 것만 유지")
    void referer_mixedQueryParams_filtersNonAllowed() {
        // /orders 경로는 "page" 파라미터만 허용
        BusinessException ex = new BusinessException("TEST", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "localhost");
        request.addHeader("Referer", "http://localhost/orders?page=2&evil=hack");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        // page만 유지, evil은 필터링
        String redirect = (String) result;
        assertThat(redirect).contains("page=2");
        assertThat(redirect).doesNotContain("evil");
    }

    // ── X-Forwarded-Host 헤더 사용 ──

    @Test
    @DisplayName("X-Forwarded-Host 헤더가 있으면 Host 대신 사용")
    void xForwardedHost_usedForTrustCheck() {
        BusinessException ex = new BusinessException("TEST", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Host", "example.com");
        request.addHeader("Host", "internal-host");
        request.addHeader("Referer", "http://example.com/cart");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        // X-Forwarded-Host(example.com) == Referer host(example.com) → 리다이렉트 허용
        assertThat(result).isEqualTo("redirect:/cart");
    }

    // ── 경로가 허용 목록에 없는 경우 ──

    @Test
    @DisplayName("Referer 경로가 허용 목록에 없으면 / 로 리다이렉트")
    void referer_unknownPath_redirectsToRoot() {
        BusinessException ex = new BusinessException("TEST", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Host", "localhost");
        request.addHeader("Referer", "http://localhost/unknown/path");
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/");
    }
}

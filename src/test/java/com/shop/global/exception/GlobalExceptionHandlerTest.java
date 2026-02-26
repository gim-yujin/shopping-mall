package com.shop.global.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("BusinessException — Referer가 있으면 원래 페이지로 리다이렉트")
    void handleBusiness_redirectsToReferer() {
        BusinessException ex = new BusinessException("ORDER_001", "주문 처리 실패");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("Referer", "http://localhost/admin/orders?page=2");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/admin/orders?page=2");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes()).containsEntry("errorMessage", "주문 처리 실패");
    }

    @Test
    @DisplayName("BusinessException — Referer가 없으면 홈으로 폴백")
    void handleBusiness_fallbackToHomeWhenNoReferer() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("BusinessException — 외부 도메인 Referer는 Open Redirect 방지로 차단")
    void handleBusiness_blocksExternalReferer() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("Referer", "https://evil.com/phishing");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("BusinessException — AJAX 요청은 JSON 응답 반환")
    void handleBusiness_returnsJsonForAjax() {
        BusinessException ex = new BusinessException("CART_001", "재고 부족");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<Map<String, String>> response = (ResponseEntity<Map<String, String>>) result;
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "CART_001");
        assertThat(response.getBody()).containsEntry("message", "재고 부족");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("BusinessException — Accept: application/json 도 AJAX로 판단")
    void handleBusiness_returnsJsonForAcceptJson() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept", "application/json");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isInstanceOf(ResponseEntity.class);
    }

    @Test
    @DisplayName("BusinessException — Referer 경로만 추출 (쿼리 파라미터 없는 경우)")
    void handleBusiness_refererWithoutQuery() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("myshop.com");
        request.addHeader("Referer", "https://myshop.com/products/123");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/products/123");
    }
}

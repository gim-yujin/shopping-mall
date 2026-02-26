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
    @DisplayName("BusinessException — 허용된 내부 경로 Referer면 원래 페이지로 리다이렉트")
    void handleBusiness_redirectsToReferer() {
        BusinessException ex = new BusinessException("ORDER_001", "주문 처리 실패");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("Host", "localhost");
        request.addHeader("Referer", "http://localhost/orders?page=2&token=secret");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/orders?page=2");
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
    @DisplayName("BusinessException — 외부 도메인 Referer는 허용 경로 화이트리스트 기반으로 차단")
    void handleBusiness_blocksExternalReferer() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("Host", "localhost");
        request.addHeader("Referer", "https://evil.com/orders?page=1");
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
    @DisplayName("BusinessException — /products/** 경로는 화이트리스트 쿼리만 유지")
    void handleBusiness_keepsWhitelistedQueryParamsForProducts() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("myshop.com");
        request.addHeader("Host", "myshop.com");
        request.addHeader("Referer", "https://myshop.com/products/123?keyword=shoe&page=2&redirect=https://evil.com");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/products/123?keyword=shoe&page=2");
    }

    @Test
    @DisplayName("BusinessException — 프록시/포트가 달라도 허용 경로면 리다이렉트")
    void handleBusiness_allowsProxyAndDifferentPortForAllowedPath() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("internal-service");
        request.setServerPort(8080);
        request.addHeader("X-Forwarded-Host", "shop.example.com");
        request.addHeader("Referer", "https://shop.example.com:8443/cart");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/cart");
    }

    @Test
    @DisplayName("BusinessException — 허용되지 않은 내부 경로 Referer는 홈으로 폴백")
    void handleBusiness_fallbackWhenPathIsNotInWhitelist() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("myshop.com");
        request.addHeader("Host", "myshop.com");
        request.addHeader("Referer", "https://myshop.com/admin/orders");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("BusinessException — Referer 경로만 추출 (쿼리 파라미터 없는 경우)")
    void handleBusiness_refererWithoutQuery() {
        BusinessException ex = new BusinessException("ERR", "에러");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("myshop.com");
        request.addHeader("Host", "myshop.com");
        request.addHeader("Referer", "https://myshop.com/products/123");
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        Object result = handler.handleBusiness(ex, request, redirectAttributes);

        assertThat(result).isEqualTo("redirect:/products/123");
    }
}

package com.shop.global.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SimpleSavedRequest;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAuthenticationSuccessHandlerTest {

    @Test
    @DisplayName("보호 URL 접근 후 로그인 성공 시 원래 요청 URL로 리다이렉트된다")
    void redirectsToSavedRequestAfterLogin() throws ServletException, IOException {
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        LoginAuthenticationSuccessHandler handler = new LoginAuthenticationSuccessHandler(loginAttemptService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContextPath("");
        request.setSession(new MockHttpSession());
        request.getSession().setAttribute(
            HttpSessionRequestCache.SAVED_REQUEST,
            new SimpleSavedRequest("http://localhost/orders")
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        when(loginAttemptService.extractClientIp(request)).thenReturn("127.0.0.1");
        when(authentication.getName()).thenReturn("user1");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost/orders");
        verify(loginAttemptService).clearFailures("user1", "127.0.0.1");
    }

    @Test
    @DisplayName("직접 로그인 페이지 진입 후 로그인 성공 시 홈으로 리다이렉트된다")
    void redirectsToHomeWhenNoSavedRequest() throws ServletException, IOException {
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        LoginAuthenticationSuccessHandler handler = new LoginAuthenticationSuccessHandler(loginAttemptService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setContextPath("");
        request.setSession(new MockHttpSession());

        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        when(loginAttemptService.extractClientIp(request)).thenReturn("127.0.0.1");
        when(authentication.getName()).thenReturn("user1");

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        verify(loginAttemptService).clearFailures("user1", "127.0.0.1");
    }
}

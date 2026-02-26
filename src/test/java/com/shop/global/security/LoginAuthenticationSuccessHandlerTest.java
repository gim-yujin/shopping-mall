package com.shop.global.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

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
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest protectedRequest = new MockHttpServletRequest("GET", "/orders");
        protectedRequest.setServerName("localhost");
        protectedRequest.setServerPort(80);
        protectedRequest.setScheme("http");
        protectedRequest.setSession(session);
        requestCache.saveRequest(protectedRequest, new MockHttpServletResponse());

        MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/auth/login");
        loginRequest.setContextPath("");
        loginRequest.setSession(session);

        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = mock(Authentication.class);

        when(loginAttemptService.extractClientIp(loginRequest)).thenReturn("127.0.0.1");
        when(authentication.getName()).thenReturn("user1");

        handler.onAuthenticationSuccess(loginRequest, response, authentication);

        assertThat(response.getRedirectedUrl()).endsWith("/orders");
        verify(loginAttemptService).extractClientIp(loginRequest);
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
        verify(loginAttemptService).extractClientIp(request);
        verify(loginAttemptService).clearFailures("user1", "127.0.0.1");
    }
}

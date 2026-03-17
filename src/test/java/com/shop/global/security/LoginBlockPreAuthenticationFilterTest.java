package com.shop.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.LockedException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * LoginBlockPreAuthenticationFilter 단위 테스트.
 *
 * 로그인 시도 전에 IP/username 기반 차단 여부를 확인하는 필터를 검증한다.
 * Spring Security 필터 체인에 등록되어 UsernamePasswordAuthenticationFilter 전에 실행되므로,
 * 차단된 사용자는 비밀번호 검증 자체를 건너뛰어 BCrypt 연산 비용을 절약한다.
 *
 * 4가지 분기를 커버한다:
 * 1. POST /auth/login + 차단되지 않은 상태 → filterChain 통과
 * 2. POST /auth/login + 차단된 상태 → failureHandler 호출 (filterChain 미통과)
 * 3. POST /auth/login이 아닌 요청 → 무조건 filterChain 통과
 * 4. GET /auth/login → 로그인 폼 요청이므로 차단 체크 안 함
 */
@ExtendWith(MockitoExtension.class)
class LoginBlockPreAuthenticationFilterTest {

    @Mock private LoginAttemptService loginAttemptService;
    @Mock private LoginAuthenticationFailureHandler failureHandler;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private LoginBlockPreAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LoginBlockPreAuthenticationFilter(loginAttemptService, failureHandler);
    }

    @Test
    @DisplayName("POST /auth/login + 차단되지 않은 상태 → filterChain 정상 통과")
    void doFilter_loginRequest_notBlocked_passesThrough() throws Exception {
        // given: 로그인 요청이지만 차단되지 않은 사용자
        when(request.getMethod()).thenReturn("POST");
        when(request.getServletPath()).thenReturn("/auth/login");
        when(request.getParameter("username")).thenReturn("testuser");
        when(loginAttemptService.extractClientIp(request)).thenReturn("127.0.0.1");
        when(loginAttemptService.isBlocked("testuser", "127.0.0.1")).thenReturn(false);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: 차단되지 않았으므로 다음 필터(UsernamePasswordAuthenticationFilter)로 진행
        verify(filterChain).doFilter(request, response);
        verify(failureHandler, never()).onAuthenticationFailure(any(), any(), any());
    }

    @Test
    @DisplayName("POST /auth/login + 차단된 상태 → failureHandler 호출, filterChain 미통과")
    void doFilter_loginRequest_blocked_callsFailureHandler() throws Exception {
        // given: LoginAttemptService가 차단 판정 (5회 연속 실패 등)
        when(request.getMethod()).thenReturn("POST");
        when(request.getServletPath()).thenReturn("/auth/login");
        when(request.getParameter("username")).thenReturn("blocked_user");
        when(loginAttemptService.extractClientIp(request)).thenReturn("10.0.0.1");
        when(loginAttemptService.isBlocked("blocked_user", "10.0.0.1")).thenReturn(true);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: 차단 시 failureHandler로 LockedException 전달, filterChain 미호출
        verify(failureHandler).onAuthenticationFailure(
                eq(request), eq(response), any(LockedException.class));
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("POST /other-path → 로그인 요청이 아니므로 차단 체크 없이 통과")
    void doFilter_nonLoginRequest_passesWithoutCheck() throws Exception {
        // given: 로그인이 아닌 다른 POST 요청 (예: 주문 생성)
        when(request.getMethod()).thenReturn("POST");
        when(request.getServletPath()).thenReturn("/api/v1/orders");

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: 로그인 경로가 아니므로 LoginAttemptService 미호출, 바로 통과
        verifyNoInteractions(loginAttemptService);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("GET /auth/login → HTTP 메서드가 POST가 아니므로 차단 체크 안 함")
    void doFilter_getLoginPage_passesWithoutCheck() throws Exception {
        // given: 로그인 폼 페이지 요청 (GET)
        // isLoginRequest()는 "POST".equalsIgnoreCase(getMethod()) && "/auth/login".equals(getServletPath())
        // && 단축 평가로 getMethod()가 "GET"이면 getServletPath()는 호출되지 않는다.
        when(request.getMethod()).thenReturn("GET");

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: GET 요청은 로그인 시도가 아니므로 차단 체크 없이 통과
        verifyNoInteractions(loginAttemptService);
        verify(filterChain).doFilter(request, response);
    }
}

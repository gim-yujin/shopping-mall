package com.shop.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class LoginBlockPreAuthenticationFilter extends OncePerRequestFilter {

    private final LoginAttemptService loginAttemptService;
    private final AuthenticationFailureHandler failureHandler;

    public LoginBlockPreAuthenticationFilter(LoginAttemptService loginAttemptService,
                                             LoginAuthenticationFailureHandler failureHandler) {
        this.loginAttemptService = loginAttemptService;
        this.failureHandler = failureHandler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isLoginRequest(request)) {
            String username = request.getParameter("username");
            String ipAddress = loginAttemptService.extractClientIp(request);

            if (loginAttemptService.isBlocked(username, ipAddress)) {
                failureHandler.onAuthenticationFailure(request, response,
                        new LockedException("Too many login failures"));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && "/auth/login".equals(request.getServletPath());
    }
}

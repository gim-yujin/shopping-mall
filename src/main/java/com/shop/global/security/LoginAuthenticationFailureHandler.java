package com.shop.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class LoginAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginAuthenticationFailureHandler.class);
    private final LoginAttemptService loginAttemptService;

    public LoginAuthenticationFailureHandler(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String username = request.getParameter("username");
        String ipAddress = loginAttemptService.extractClientIp(request);

        if (exception instanceof LockedException) {
            long remaining = loginAttemptService.getRemainingBlockSeconds(username, ipAddress);
            log.warn("event=login_blocked username={} ip={} reason=throttled_before_auth retry_after_sec={}",
                    mask(username), ipAddress, remaining);
        } else {
            long delaySec = loginAttemptService.recordFailure(username, ipAddress);
            log.warn("event=login_fail username={} ip={} reason=bad_credentials next_delay_sec={}",
                    mask(username), ipAddress, delaySec);
        }

        response.sendRedirect("/auth/login?error=true");
    }

    private String mask(String username) {
        if (username == null || username.isBlank()) {
            return "anonymous";
        }

        String normalized = username.trim();
        int visibleChars = Math.min(2, normalized.length());
        return normalized.substring(0, visibleChars) + "***";
    }
}

package com.shop.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class LoginAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final LoginAttemptService loginAttemptService;
    private final SavedRequestAwareAuthenticationSuccessHandler delegate;

    public LoginAuthenticationSuccessHandler(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
        this.delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        this.delegate.setDefaultTargetUrl("/");
        this.delegate.setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String ipAddress = loginAttemptService.extractClientIp(request);
        loginAttemptService.clearFailures(authentication.getName(), ipAddress);
        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}

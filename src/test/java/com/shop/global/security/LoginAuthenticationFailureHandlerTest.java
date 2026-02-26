package com.shop.global.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.slf4j.LoggerFactory.getLogger;

class LoginAuthenticationFailureHandlerTest {

    private final Logger logger = (Logger) getLogger(LoginAuthenticationFailureHandler.class);

    @AfterEach
    void tearDown() {
        logger.detachAndStopAllAppenders();
    }

    @Test
    @DisplayName("인증 실패 로그는 필드명을 유지하면서 username을 마스킹한다")
    void logsMaskedUsernameOnFailureWithFixedFieldNames() throws ServletException, IOException {
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        LoginAuthenticationFailureHandler handler = new LoginAuthenticationFailureHandler(loginAttemptService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setParameter("username", "alice@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(loginAttemptService.extractClientIp(request)).thenReturn("10.0.0.8");
        when(loginAttemptService.recordFailure("alice@example.com", "10.0.0.8")).thenReturn(8L);

        ListAppender<ILoggingEvent> listAppender = attachListAppender();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/login?error=true");
        assertThat(listAppender.list).hasSize(1);

        ILoggingEvent event = listAppender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("event=login_fail username=al*** ip=10.0.0.8 reason=bad_credentials next_delay_sec=8");
    }

    @Test
    @DisplayName("차단 로그도 필드명을 유지하면서 username을 마스킹한다")
    void logsMaskedUsernameOnBlockedWithFixedFieldNames() throws ServletException, IOException {
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        LoginAuthenticationFailureHandler handler = new LoginAuthenticationFailureHandler(loginAttemptService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setParameter("username", "a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(loginAttemptService.extractClientIp(request)).thenReturn("10.0.0.9");
        when(loginAttemptService.getRemainingBlockSeconds("a", "10.0.0.9")).thenReturn(120L);

        ListAppender<ILoggingEvent> listAppender = attachListAppender();

        handler.onAuthenticationFailure(request, response, new LockedException("locked"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/login?error=true");
        assertThat(listAppender.list).hasSize(1);

        ILoggingEvent event = listAppender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .isEqualTo("event=login_blocked username=a*** ip=10.0.0.9 reason=throttled_before_auth retry_after_sec=120");
    }

    @Test
    @DisplayName("username이 비어있으면 anonymous로 기록한다")
    void logsAnonymousWhenUsernameIsBlank() throws ServletException, IOException {
        LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
        LoginAuthenticationFailureHandler handler = new LoginAuthenticationFailureHandler(loginAttemptService);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setParameter("username", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(loginAttemptService.extractClientIp(request)).thenReturn("10.0.0.10");
        when(loginAttemptService.recordFailure("   ", "10.0.0.10")).thenReturn(1L);

        ListAppender<ILoggingEvent> listAppender = attachListAppender();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad credentials"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/login?error=true");
        assertThat(listAppender.list).hasSize(1);
        assertThat(listAppender.list.getFirst().getFormattedMessage())
                .contains("event=login_fail")
                .contains("username=anonymous")
                .contains("ip=10.0.0.10")
                .contains("reason=bad_credentials")
                .contains("next_delay_sec=1");
    }

    private ListAppender<ILoggingEvent> attachListAppender() {
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        return listAppender;
    }
}

package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService(
            new ConcurrentMapCacheManager("loginAttempts"),
            List.of("10.0.0.0/8", "127.0.0.1/32"),
            1
    );

    @Test
    @DisplayName("remoteAddr가 trusted proxy가 아니면 X-Forwarded-For를 무시한다")
    void extractClientIp_shouldIgnoreForwardedHeaders_whenRemoteIsUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "1.1.1.1");
        request.addHeader("X-Real-IP", "2.2.2.2");

        String resolved = service.extractClientIp(request);

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("trusted proxy 체인에서는 오른쪽 trusted hop을 제거하고 첫 untrusted IP를 선택한다")
    void extractClientIp_shouldSelectFirstUntrustedIp_fromProxyChain() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.11");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.10");

        String resolved = service.extractClientIp(request);

        assertThat(resolved).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("spoofed X-Forwarded-For만으로는 차단 키 우회가 불가능하다")
    void loginBlockKey_shouldNotBeBypassedBySpoofedForwardedForAlone() {
        String username = "attacker";

        MockHttpServletRequest normal = new MockHttpServletRequest();
        normal.setRemoteAddr("198.51.100.20");

        String normalIp = service.extractClientIp(normal);
        service.recordFailure(username, normalIp);

        MockHttpServletRequest spoofed = new MockHttpServletRequest();
        spoofed.setRemoteAddr("198.51.100.20");
        spoofed.addHeader("X-Forwarded-For", "8.8.8.8");

        String spoofedIp = service.extractClientIp(spoofed);

        assertThat(spoofedIp).isEqualTo(normalIp);
        assertThat(service.isBlocked(username, spoofedIp)).isTrue();
    }
}

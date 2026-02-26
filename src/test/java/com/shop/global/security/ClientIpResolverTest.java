package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    @DisplayName("프록시가 없는 환경에서는 remoteAddr를 그대로 클라이언트 IP로 사용한다")
    void resolveClientIp_withoutProxy_usesRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.5");

        String resolved = resolver.resolveClientIp(request);

        assertThat(resolved).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("trusted proxy 환경에서는 X-Forwarded-For에서 원본 클라이언트 IP를 해석한다")
    void resolveClientIp_withTrustedProxy_usesForwardedChain() {
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.11");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.10");

        String resolved = resolver.resolveClientIp(request);

        assertThat(resolved).isEqualTo("203.0.113.5");
    }
}

package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClientIpResolver 분기 커버리지 보강 테스트.
 *
 * <p>기존 ClientIpResolverTest(통합 테스트)에서 다루지 않은 분기를 검증한다:
 * - trustedProxyCidrs가 빈 목록인 경우 (프록시 없는 환경)
 * - X-Forwarded-For가 빈 문자열인 경우
 * - X-Real-IP 헤더 사용 분기
 * - X-Real-IP가 빈 문자열인 분기
 * - X-Real-IP 자체가 trusted proxy인 분기
 * - 포워딩 체인에서 모든 IP가 trusted인 분기
 * - buildMatchers에 null/blank CIDR이 포함된 분기
 * - isTrustedProxy: null/blank IP 입력 분기</p>
 */
class ClientIpResolverBranchTest {

    // ── 프록시 없는 환경 ──

    @Test
    @DisplayName("trustedProxyCidrs가 빈 목록이면 항상 remoteAddr 반환")
    void noTrustedProxies_alwaysReturnsRemoteAddr() {
        // given: 프록시 설정이 없는 환경
        // isTrustedProxy에서 trustedProxyMatchers.isEmpty() → false 반환
        ClientIpResolver resolver = new ClientIpResolver(Collections.emptyList(), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        request.addHeader("X-Forwarded-For", "10.0.0.1");

        // when: remoteAddr이 trusted proxy가 아니므로 바로 반환
        String ip = resolver.resolveClientIp(request);

        // then: X-Forwarded-For 무시, remoteAddr 반환
        assertThat(ip).isEqualTo("192.168.1.100");
    }

    // ── X-Forwarded-For 빈 문자열 분기 ──

    @Test
    @DisplayName("X-Forwarded-For가 빈 문자열이면 X-Real-IP로 폴백")
    void trustedProxy_blankForwarded_fallsToRealIp() {
        // given: trusted proxy에서 X-Forwarded-For가 빈 문자열
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // trusted proxy
        request.addHeader("X-Forwarded-For", "   "); // 빈 문자열 → isBlank() true
        request.addHeader("X-Real-IP", "203.0.113.50");

        // when: X-Forwarded-For 빈 → X-Real-IP 폴백
        String ip = resolver.resolveClientIp(request);

        // then: X-Real-IP 값 반환
        assertThat(ip).isEqualTo("203.0.113.50");
    }

    // ── X-Real-IP 분기 ──

    @Test
    @DisplayName("X-Forwarded-For가 없고 X-Real-IP가 있으면 X-Real-IP 반환")
    void trustedProxy_noForwarded_usesRealIp() {
        // given: X-Forwarded-For 없이 X-Real-IP만 있는 요청
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // trusted proxy

        request.addHeader("X-Real-IP", "198.51.100.10");

        // when
        String ip = resolver.resolveClientIp(request);

        // then: X-Real-IP 값 반환
        assertThat(ip).isEqualTo("198.51.100.10");
    }

    @Test
    @DisplayName("X-Real-IP가 빈 문자열이면 remoteAddr로 폴백")
    void trustedProxy_blankRealIp_fallsToRemoteAddr() {
        // given: X-Real-IP가 빈 문자열
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Real-IP", "  "); // isBlank() true

        // when: X-Real-IP 빈 → remoteAddr 폴백
        String ip = resolver.resolveClientIp(request);

        // then
        assertThat(ip).isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("X-Real-IP가 trusted proxy이면 remoteAddr로 폴백")
    void trustedProxy_realIpIsTrusted_fallsToRemoteAddr() {
        // given: X-Real-IP 자체가 trusted proxy인 경우
        // isTrustedProxy(normalized) → true → 폴백
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Real-IP", "10.0.0.2"); // trusted proxy 범위

        // when: trusted IP → 무시 → remoteAddr 폴백
        String ip = resolver.resolveClientIp(request);

        // then
        assertThat(ip).isEqualTo("10.0.0.1");
    }

    // ── 포워딩 체인에서 모든 IP가 trusted인 경우 ──

    @Test
    @DisplayName("포워딩 체인의 모든 IP가 trusted이면 remoteAddr 반환")
    void allTrustedChain_returnsRemoteAddr() {
        // given: 포워딩 체인에 trusted IP만 있는 경우
        // resolveFirstUntrustedFromChain에서 모든 후보가 trusted → break → remoteAddr 폴백
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 2);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.2, 10.0.0.3");

        // when: 모든 IP가 trusted → untrusted 후보 없음
        String ip = resolver.resolveClientIp(request);

        // then: remoteAddr 폴백
        assertThat(ip).isEqualTo("10.0.0.1");
    }

    // ── buildMatchers: null/blank CIDR 필터링 ──

    @Test
    @DisplayName("CIDR 목록에 null/blank 항목이 있으면 무시한다")
    void buildMatchers_nullAndBlankCidrs_filtered() {
        // given: null과 빈 문자열이 섞인 CIDR 목록
        // buildMatchers에서 cidr != null && !cidr.isBlank() 필터링 분기
        ClientIpResolver resolver = new ClientIpResolver(
                java.util.Arrays.asList(null, "", "  ", "10.0.0.0/8"), 1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // trusted (유효한 CIDR에 매칭)
        request.addHeader("X-Forwarded-For", "203.0.113.1");

        // when: null/blank CIDR은 무시되고 10.0.0.0/8만 적용
        String ip = resolver.resolveClientIp(request);

        // then: 10.0.0.1이 trusted → X-Forwarded-For에서 클라이언트 IP 추출
        assertThat(ip).isEqualTo("203.0.113.1");
    }

    // ── trustedHopCount 음수 보정 ──

    @Test
    @DisplayName("trustedHopCount가 음수면 0으로 보정된다")
    void negativeHopCount_correctedToZero() {
        // given: trustedHopCount = -1 → Math.max(-1, 0) = 0
        // trustedConsumed < trustedHopCount(0)이므로 trusted proxy를 소비하지 않음
        ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), -1);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.2");

        // when: hopCount=0이므로 trusted hop을 소비하지 않음
        // 체인 뒤에서부터: 10.0.0.2(trusted, 소비 안 함) → break → remoteAddr
        String ip = resolver.resolveClientIp(request);

        // then
        assertThat(ip).isEqualTo("10.0.0.1");
    }
}

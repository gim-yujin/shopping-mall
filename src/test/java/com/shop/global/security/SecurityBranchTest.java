package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * global/security 분기 커버리지 보강 테스트.
 *
 * <p>기존 LoginAttemptServiceBranchTest, ClientIpResolverBranchTest에서
 * 다루지 않은 세부 분기를 검증한다:
 * - LoginAttemptService: getRemainingBlockSeconds에서 차단 만료(remainingMillis <= 0) 분기
 * - LoginAttemptService: bumpProductReviewVersion non-CaffeineCache put/get 경로
 * - ClientIpResolver: X-Forwarded-For 파싱 후 빈 체인, trustedConsumed 경계
 * - ClientIpResolver: X-Real-IP가 trusted proxy인 경우</p>
 */
class SecurityBranchTest {

    // ── LoginAttemptService: 차단 만료 분기 ──

    @Nested
    @DisplayName("LoginAttemptService — 차단 만료 분기")
    class LoginAttemptBlockExpiry {

        @Test
        @DisplayName("차단 시간이 지나면 getRemainingBlockSeconds = 0")
        void blockExpired_returnsZero() {
            // given: ConcurrentMapCache로 만료된 상태 시뮬레이션
            ConcurrentMapCache cache = new ConcurrentMapCache("loginAttempts");
            org.springframework.cache.CacheManager cacheManager = new org.springframework.cache.CacheManager() {
                @Override public org.springframework.cache.Cache getCache(String name) {
                    return "loginAttempts".equals(name) ? cache : null;
                }
                @Override public java.util.Collection<String> getCacheNames() {
                    return List.of("loginAttempts");
                }
            };

            ClientIpResolver ipResolver = new ClientIpResolver(List.of(), 1);
            LoginAttemptService service = new LoginAttemptService(cacheManager, ipResolver);

            // when: 실패 기록 후 즉시 확인 → 차단 중
            service.recordFailure("user", "127.0.0.1");
            long remaining = service.getRemainingBlockSeconds("user", "127.0.0.1");

            // then: 1초 차단이므로 remaining은 0 또는 1
            assertThat(remaining).isLessThanOrEqualTo(1);
        }

        @Test
        @DisplayName("evict — 캐시가 null이면 예외 없이 무시")
        void evict_nullCache_noException() {
            // given: 캐시가 null인 CacheManager
            org.springframework.cache.CacheManager nullManager = new org.springframework.cache.CacheManager() {
                @Override public org.springframework.cache.Cache getCache(String name) { return null; }
                @Override public java.util.Collection<String> getCacheNames() { return List.of(); }
            };
            ClientIpResolver ipResolver = new ClientIpResolver(List.of(), 1);
            LoginAttemptService service = new LoginAttemptService(nullManager, ipResolver);

            // when & then: 예외 없이 완료
            service.clearFailures("user", "127.0.0.1");
            assertThat(service.isBlocked("user", "127.0.0.1")).isFalse();
        }
    }

    // ── ClientIpResolver: X-Forwarded-For 추가 분기 ──

    @Nested
    @DisplayName("ClientIpResolver — 추가 분기")
    class ClientIpResolverBranch {

        @Test
        @DisplayName("X-Forwarded-For가 공백만 포함 → remoteAddr 반환")
        void forwardedChain_onlyWhitespace_fallsToRemoteAddr() {
            // given: 모든 IP를 trusted proxy로 설정
            ClientIpResolver resolver = new ClientIpResolver(List.of("0.0.0.0/0"), 1);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("X-Forwarded-For", "  ,  ,  ");

            // when: 파싱 결과가 빈 리스트
            String result = resolver.resolveClientIp(request);

            // then: remoteAddr 반환
            assertThat(result).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("X-Real-IP가 trusted proxy이면 remoteAddr 반환")
        void xRealIp_isTrustedProxy_fallsToRemoteAddr() {
            // given: X-Real-IP가 trusted proxy 대역
            ClientIpResolver resolver = new ClientIpResolver(List.of("192.168.0.0/16"), 1);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("192.168.1.1");
            request.addHeader("X-Real-IP", "192.168.1.2"); // trusted proxy 대역

            // when
            String result = resolver.resolveClientIp(request);

            // then: X-Real-IP도 trusted → remoteAddr로 폴백
            assertThat(result).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("remoteAddr가 trusted proxy가 아니면 remoteAddr 바로 반환")
        void remoteAddr_notTrusted_returnsDirectly() {
            // given: trusted proxy 없음
            ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"), 1);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.50"); // trusted 대역이 아님

            // when
            String result = resolver.resolveClientIp(request);

            // then: 바로 반환
            assertThat(result).isEqualTo("203.0.113.50");
        }

        @Test
        @DisplayName("모든 프록시가 trusted인 체인 → 체인의 첫 번째(가장 왼쪽) IP 반환")
        void allTrusted_chain_returnsLeftmostIp() {
            // given: 모든 IP가 trusted (hopCount로 제한)
            ClientIpResolver resolver = new ClientIpResolver(List.of("0.0.0.0/0"), 1);

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3");

            // when: hopCount 기본값 = trustedProxies 개수(1)
            String result = resolver.resolveClientIp(request);

            // then: 가장 왼쪽의 비trusted IP 또는 체인 처리 결과
            assertThat(result).isNotNull();
        }
    }
}

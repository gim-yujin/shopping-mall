package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Test
    @DisplayName("동일 키로 병렬 실패 요청 시 카운트가 유실되지 않는다")
    void recordFailure_shouldIncreaseCountAtomically_underConcurrentRequests() throws Exception {
        String username = "parallel-user";
        String ipAddress = "203.0.113.10";

        int attempts = 64;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                tasks.add(() -> service.recordFailure(username, ipAddress));
            }

            List<Future<Long>> futures = executor.invokeAll(tasks);
            for (Future<Long> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        LoginAttemptService.AttemptState state = service.getStateForTest(username, ipAddress);
        assertThat(state).isNotNull();
        assertThat(state.failureCount()).isEqualTo(attempts);
        assertThat(service.isBlocked(username, ipAddress)).isTrue();
    }

    @Test
    @DisplayName("실패 누적에 따른 차단 시간 계산은 최대 5분으로 캡된다")
    void recordFailure_shouldKeepBackoffPolicy_withFiveMinuteCap() {
        String username = "cap-user";
        String ipAddress = "198.51.100.33";

        long first = service.recordFailure(username, ipAddress);
        assertThat(first).isEqualTo(1);

        long second = service.recordFailure(username, ipAddress);
        assertThat(second).isEqualTo(2);

        long ninth = 0;
        for (int i = 0; i < 7; i++) {
            ninth = service.recordFailure(username, ipAddress);
        }
        assertThat(ninth).isEqualTo(256);

        long tenth = service.recordFailure(username, ipAddress);
        assertThat(tenth).isEqualTo(300);

        LoginAttemptService.AttemptState state = service.getStateForTest(username, ipAddress);
        assertThat(state).isNotNull();
        assertThat(state.failureCount()).isEqualTo(10);

        long remaining = service.getRemainingBlockSeconds(username, ipAddress);
        assertThat(remaining).isBetween(1L, 300L);
    }

}

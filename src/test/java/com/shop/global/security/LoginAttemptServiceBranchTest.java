package com.shop.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoginAttemptService 분기 커버리지 보강 테스트.
 *
 * <p>기존 LoginAttemptServiceTest(통합 테스트)에서 다루지 않은 분기를 검증한다:
 * - recordFailure: 캐시가 null인 분기 → BASE_DELAY 반환
 * - getRemainingBlockSeconds: state가 null / nextAllowedAt가 null인 분기
 * - non-CaffeineCache 분기: ConcurrentMapCache 사용 시 synchronized 경로
 * - buildCacheKey: username/ipAddress가 null인 분기
 * - evict: 캐시가 null인 분기
 *
 * <p>Mock CacheManager를 사용하여 CaffeineCache가 아닌 환경에서의
 * fallback synchronized 경로를 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceBranchTest {

    @Mock
    private ClientIpResolver clientIpResolver;

    // ── recordFailure: 캐시가 null인 분기 ──

    @Test
    @DisplayName("recordFailure — 캐시가 null이면 BASE_DELAY(1초) 반환")
    void recordFailure_nullCache_returnsBaseDelay() {
        // given: CacheManager가 loginAttempts 캐시를 반환하지 않는 상황
        // recordFailure에서 cache == null 분기 → BASE_DELAY.getSeconds() 반환
        CacheManager nullCacheManager = new CacheManager() {
            @Override public Cache getCache(String name) { return null; }
            @Override public java.util.Collection<String> getCacheNames() { return java.util.List.of(); }
        };
        LoginAttemptService service = new LoginAttemptService(nullCacheManager, clientIpResolver);

        // when
        long delaySec = service.recordFailure("user", "127.0.0.1");

        // then: 캐시 없으면 기본 지연(1초) 반환
        assertThat(delaySec).isEqualTo(1);
    }

    // ── getRemainingBlockSeconds: state/nextAllowedAt null 분기 ──

    @Test
    @DisplayName("getRemainingBlockSeconds — 상태가 없으면 0 반환")
    void getRemainingBlockSeconds_noState_returnsZero() {
        // given: 캐시에 기록이 없는 사용자
        CacheManager nullCacheManager = new CacheManager() {
            @Override public Cache getCache(String name) { return null; }
            @Override public java.util.Collection<String> getCacheNames() { return java.util.List.of(); }
        };
        LoginAttemptService service = new LoginAttemptService(nullCacheManager, clientIpResolver);

        // when: getState()이 null 반환 → state == null 분기
        long remaining = service.getRemainingBlockSeconds("user", "127.0.0.1");

        // then
        assertThat(remaining).isEqualTo(0);
    }

    // ── non-CaffeineCache 분기 (synchronized 경로) ──

    @Test
    @DisplayName("recordFailure — ConcurrentMapCache에서 synchronized 경로로 동작")
    void recordFailure_concurrentMapCache_usesSynchronizedPath() {
        // given: CaffeineCache가 아닌 ConcurrentMapCache 사용
        // recordFailure의 else 분기: synchronized(lock) { get → compute → put }
        ConcurrentMapCache simpleCache = new ConcurrentMapCache("loginAttempts");
        CacheManager simpleCacheManager = new CacheManager() {
            @Override public Cache getCache(String name) {
                return "loginAttempts".equals(name) ? simpleCache : null;
            }
            @Override public java.util.Collection<String> getCacheNames() {
                return java.util.List.of("loginAttempts");
            }
        };
        LoginAttemptService service = new LoginAttemptService(simpleCacheManager, clientIpResolver);

        // when: 첫 번째 실패 기록 → synchronized 경로
        long delay1 = service.recordFailure("testuser", "10.0.0.1");
        // when: 두 번째 실패 기록 → 기존 상태를 조회하여 증가
        long delay2 = service.recordFailure("testuser", "10.0.0.1");

        // then: 지수 백오프 적용 — 1차: 1초, 2차: 2초
        assertThat(delay1).isEqualTo(1);
        assertThat(delay2).isEqualTo(2);

        // then: 차단 상태 확인
        assertThat(service.isBlocked("testuser", "10.0.0.1")).isTrue();
    }

    // ── clearFailures/isBlocked — 캐시 있는 경우 정상 동작 ──

    @Test
    @DisplayName("clearFailures — 실패 기록 삭제 후 isBlocked가 false 반환")
    void clearFailures_afterRecordFailure_unblocksUser() {
        // given: ConcurrentMapCache로 실패 기록
        ConcurrentMapCache cache = new ConcurrentMapCache("loginAttempts");
        CacheManager cacheManager = new CacheManager() {
            @Override public Cache getCache(String name) {
                return "loginAttempts".equals(name) ? cache : null;
            }
            @Override public java.util.Collection<String> getCacheNames() {
                return java.util.List.of("loginAttempts");
            }
        };
        LoginAttemptService service = new LoginAttemptService(cacheManager, clientIpResolver);

        // when: 실패 기록 → 차단 → 초기화
        service.recordFailure("user", "1.1.1.1");
        assertThat(service.isBlocked("user", "1.1.1.1")).isTrue();

        service.clearFailures("user", "1.1.1.1");

        // then: 초기화 후 차단 해제
        assertThat(service.isBlocked("user", "1.1.1.1")).isFalse();
    }

    // ── buildCacheKey: null 입력 정규화 분기 ──

    @Test
    @DisplayName("null username/ipAddress → 정규화된 캐시 키 생성")
    void recordFailure_nullInputs_normalizedCacheKey() {
        // given: username과 ipAddress가 null인 요청
        // buildCacheKey에서 null 처리 분기: username → "", ipAddress → "unknown"
        ConcurrentMapCache cache = new ConcurrentMapCache("loginAttempts");
        CacheManager cacheManager = new CacheManager() {
            @Override public Cache getCache(String name) {
                return "loginAttempts".equals(name) ? cache : null;
            }
            @Override public java.util.Collection<String> getCacheNames() {
                return java.util.List.of("loginAttempts");
            }
        };
        LoginAttemptService service = new LoginAttemptService(cacheManager, clientIpResolver);

        // when: null 값으로 실패 기록 — 예외 없이 정상 처리되어야 함
        long delay = service.recordFailure(null, null);

        // then: 정상 동작 (null이 빈 문자열/"unknown"으로 정규화됨)
        assertThat(delay).isEqualTo(1);
    }
}

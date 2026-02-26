package com.shop.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class LoginAttemptService {

    private static final String LOGIN_ATTEMPT_CACHE = "loginAttempts";
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(5);

    private final CacheManager cacheManager;
    private final List<IpAddressMatcher> trustedProxyMatchers;
    private final int trustedHopCount;

    public LoginAttemptService(
            CacheManager cacheManager,
            @Value("${security.login-attempt.trusted-proxy-cidrs:}") List<String> trustedProxyCidrs,
            @Value("${security.login-attempt.trusted-hop-count:1}") int trustedHopCount
    ) {
        this.cacheManager = cacheManager;
        this.trustedProxyMatchers = buildMatchers(trustedProxyCidrs);
        this.trustedHopCount = Math.max(trustedHopCount, 0);
    }

    public boolean isBlocked(String username, String ipAddress) {
        return getRemainingBlockSeconds(username, ipAddress) > 0;
    }

    public long getRemainingBlockSeconds(String username, String ipAddress) {
        AttemptState state = getState(buildCacheKey(username, ipAddress));
        if (state == null || state.nextAllowedAt() == null) {
            return 0;
        }

        long remainingMillis = Duration.between(Instant.now(), state.nextAllowedAt()).toMillis();
        if (remainingMillis <= 0) {
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    public long recordFailure(String username, String ipAddress) {
        String cacheKey = buildCacheKey(username, ipAddress);
        AttemptState current = Optional.ofNullable(getState(cacheKey))
                .orElse(new AttemptState(0, null));

        int nextFailures = current.failureCount() + 1;
        Duration backoff = calculateBackoff(nextFailures);
        Instant nextAllowedAt = Instant.now().plus(backoff);

        put(cacheKey, new AttemptState(nextFailures, nextAllowedAt));
        return backoff.getSeconds();
    }

    public void clearFailures(String username, String ipAddress) {
        evict(buildCacheKey(username, ipAddress));
    }

    public String extractClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            List<String> forwardedChain = parseForwardedChain(forwarded);
            if (!forwardedChain.isEmpty()) {
                return resolveFirstUntrustedFromChain(forwardedChain, remoteAddr);
            }
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            String normalized = realIp.trim();
            if (!normalized.isEmpty() && !isTrustedProxy(normalized)) {
                return normalized;
            }
        }

        return remoteAddr;
    }

    /**
     * X-Forwarded-For 체인은 "client, proxy1, proxy2" 형태이며 오른쪽이 애플리케이션에 가까운 hop이다.
     * trusted hop 수만큼 오른쪽부터 trusted proxy를 제거한 뒤, 남는 첫 번째 untrusted IP를 클라이언트 IP로 사용한다.
     */
    private String resolveFirstUntrustedFromChain(List<String> forwardedChain, String remoteAddr) {
        int trustedConsumed = 0;
        for (int i = forwardedChain.size() - 1; i >= 0; i--) {
            String candidate = forwardedChain.get(i);
            if (isTrustedProxy(candidate) && trustedConsumed < trustedHopCount) {
                trustedConsumed++;
                continue;
            }
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
            break;
        }
        return remoteAddr;
    }

    private List<String> parseForwardedChain(String forwarded) {
        String[] split = forwarded.split(",");
        List<String> result = new ArrayList<>();
        for (String ip : split) {
            String normalized = ip.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean isTrustedProxy(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || trustedProxyMatchers.isEmpty()) {
            return false;
        }
        return trustedProxyMatchers.stream().anyMatch(matcher -> matcher.matches(ipAddress));
    }

    private List<IpAddressMatcher> buildMatchers(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            return Collections.emptyList();
        }
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : cidrs) {
            if (cidr != null && !cidr.isBlank()) {
                matchers.add(new IpAddressMatcher(cidr.trim()));
            }
        }
        return matchers;
    }

    private Duration calculateBackoff(int failureCount) {
        long seconds;
        if (failureCount <= 1) {
            seconds = BASE_DELAY.getSeconds();
        } else {
            long exponent = Math.min(failureCount - 1, 16);
            seconds = 1L << exponent;
        }
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.getSeconds()));
    }

    private AttemptState getState(String key) {
        Cache cache = cacheManager.getCache(LOGIN_ATTEMPT_CACHE);
        if (cache == null) {
            return null;
        }
        return cache.get(key, AttemptState.class);
    }

    private void put(String key, AttemptState state) {
        Cache cache = cacheManager.getCache(LOGIN_ATTEMPT_CACHE);
        if (cache != null) {
            cache.put(key, state);
        }
    }

    private void evict(String key) {
        Cache cache = cacheManager.getCache(LOGIN_ATTEMPT_CACHE);
        if (cache != null) {
            cache.evict(key);
        }
    }

    private String buildCacheKey(String username, String ipAddress) {
        String normalizedUsername = username == null ? "" : username.trim().toLowerCase();
        String normalizedIp = ipAddress == null ? "unknown" : ipAddress.trim();
        return normalizedUsername + "|" + normalizedIp;
    }

    public record AttemptState(int failureCount, Instant nextAllowedAt) {
    }
}

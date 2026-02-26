package com.shop.global.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class LoginAttemptService {

    private static final String LOGIN_ATTEMPT_CACHE = "loginAttempts";
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(5);

    private final CacheManager cacheManager;

    public LoginAttemptService(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
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
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
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

package com.shop.global.security;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class LoginAttemptService {

    private static final String LOGIN_ATTEMPT_CACHE = "loginAttempts";
    private static final Duration BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(5);
    private static final int FALLBACK_LOCK_BUCKETS = 64;

    private final CacheManager cacheManager;
    private final ClientIpResolver clientIpResolver;
    private final Object[] fallbackLocks;

    public LoginAttemptService(CacheManager cacheManager, ClientIpResolver clientIpResolver) {
        this.cacheManager = cacheManager;
        this.clientIpResolver = clientIpResolver;
        this.fallbackLocks = buildFallbackLocks();
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
        Cache cache = cacheManager.getCache(LOGIN_ATTEMPT_CACHE);
        if (cache == null) {
            return BASE_DELAY.getSeconds();
        }

        AttemptState nextState;
        if (cache instanceof CaffeineCache caffeineCache) {
            Object updated = caffeineCache.getNativeCache().asMap().compute(cacheKey, (key, existing) ->
                    nextAttemptState(existing instanceof AttemptState state ? state : null)
            );
            nextState = updated instanceof AttemptState state ? state : nextAttemptState(null);
        } else {
            Object lock = lockFor(cacheKey);
            synchronized (lock) {
                AttemptState current = cache.get(cacheKey, AttemptState.class);
                nextState = nextAttemptState(current);
                cache.put(cacheKey, nextState);
            }
        }

        return calculateBackoff(nextState.failureCount()).getSeconds();
    }

    public void clearFailures(String username, String ipAddress) {
        evict(buildCacheKey(username, ipAddress));
    }

    public String extractClientIp(jakarta.servlet.http.HttpServletRequest request) {
        return clientIpResolver.resolveClientIp(request);
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

    private AttemptState nextAttemptState(AttemptState current) {
        AttemptState previous = Optional.ofNullable(current).orElse(new AttemptState(0, null));
        int nextFailures = previous.failureCount() + 1;
        Duration backoff = calculateBackoff(nextFailures);
        return new AttemptState(nextFailures, Instant.now().plus(backoff));
    }

    private Object[] buildFallbackLocks() {
        Object[] locks = new Object[FALLBACK_LOCK_BUCKETS];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private Object lockFor(String key) {
        return fallbackLocks[Math.floorMod(key.hashCode(), fallbackLocks.length)];
    }

    AttemptState getStateForTest(String username, String ipAddress) {
        return getState(buildCacheKey(username, ipAddress));
    }

    private AttemptState getState(String key) {
        Cache cache = cacheManager.getCache(LOGIN_ATTEMPT_CACHE);
        if (cache == null) {
            return null;
        }
        return cache.get(key, AttemptState.class);
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

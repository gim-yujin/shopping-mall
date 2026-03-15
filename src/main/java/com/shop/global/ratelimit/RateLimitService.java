package com.shop.global.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 사용자/IP별 토큰 버킷을 관리하는 서비스.
 *
 * <h3>버킷 식별 전략</h3>
 * <ul>
 *   <li>인증된 사용자: {@code userId + ":" + plan} → 사용자 단위 속도 제한</li>
 *   <li>비인증 사용자: {@code "anon:" + clientIp + ":" + plan} → IP 단위 속도 제한</li>
 * </ul>
 *
 * <p>플랜별로 키가 분리되므로, 같은 사용자가 READ 한도를 소진해도
 * ORDER 한도에는 영향을 주지 않는다 (독립적 rate limit).</p>
 *
 * <h3>메모리 관리</h3>
 * <p>Caffeine의 {@code expireAfterAccess(5분)}으로 비활성 버킷을 자동 제거한다.
 * 최대 10만 항목으로 제한하여 메모리 사용량을 통제한다.
 * 정상 트래픽에서 동시 접속자 1만명 × 5개 플랜 = 5만 항목이므로 충분한 여유가 있다.</p>
 *
 * <h3>왜 CacheManager가 아닌 독립 Caffeine Cache를 사용하는가?</h3>
 * <p>CacheConfig의 Spring CacheManager는 {@code @Cacheable} 어노테이션용이며,
 * TTL/maxSize가 도메인 캐시에 최적화되어 있다. Rate Limit 버킷은
 * 접근 후 5분 만료(expireAfterAccess)가 필요하고, CacheManager의
 * expireAfterWrite 정책과 충돌한다. 독립 Cache로 격리하여
 * 도메인 캐시와 rate limit 캐시의 생명주기를 분리한다.</p>
 */
@Service
public class RateLimitService {

    /**
     * 버킷 키 → TokenBucket 매핑.
     *
     * expireAfterAccess: 마지막 접근 후 5분 동안 요청이 없으면 버킷 제거.
     * maximumSize: 메모리 제한. 초과 시 LRU 정책으로 오래된 버킷부터 제거.
     */
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    /**
     * 인증된 사용자의 요청에 대해 토큰 소비를 시도한다.
     *
     * @param userId 인증된 사용자 ID
     * @param plan   적용할 rate limit 플랜
     * @return 소비 결과 (허용 여부 + 잔여 토큰 수)
     */
    public TokenBucket.ConsumeResult tryConsume(Long userId, RateLimitPlan plan) {
        String key = userId + ":" + plan.name();
        TokenBucket bucket = buckets.get(key, k -> plan.createBucket());
        return bucket.tryConsume();
    }

    /**
     * 비인증 사용자의 요청에 대해 토큰 소비를 시도한다.
     *
     * @param clientIp 클라이언트 IP 주소
     * @param plan     적용할 rate limit 플랜
     * @return 소비 결과 (허용 여부 + 잔여 토큰 수)
     */
    public TokenBucket.ConsumeResult tryConsumeAnonymous(String clientIp, RateLimitPlan plan) {
        String key = "anon:" + clientIp + ":" + plan.name();
        TokenBucket bucket = buckets.get(key, k -> plan.createBucket());
        return bucket.tryConsume();
    }

    /**
     * 해당 플랜의 버킷 용량(최대 토큰 수)을 반환한다.
     * HTTP 응답 헤더 {@code X-RateLimit-Limit}에 사용된다.
     */
    public int getLimit(RateLimitPlan plan) {
        return plan.getCapacity();
    }
}

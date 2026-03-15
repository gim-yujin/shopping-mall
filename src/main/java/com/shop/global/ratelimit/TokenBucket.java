package com.shop.global.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 토큰 버킷(Token Bucket) 알고리즘 구현.
 *
 * <h3>도입 배경</h3>
 * <p>로그인에는 {@code LoginAttemptService}로 속도 제한이 있지만,
 * 주문 생성, 쿠폰 발급 등 핵심 API에는 속도 제한이 없었다.
 * 선착순 쿠폰 러시, 악의적 반복 주문 시도, 크롤링/DDoS 등에서
 * 서버를 보호할 메커니즘이 부재했다.</p>
 *
 * <h3>왜 토큰 버킷인가?</h3>
 * <p>Leaky Bucket 대비 짧은 트래픽 버스트를 허용하면서도 평균 속도를 제한한다.
 * 예: 초당 10개 한도에서 잠깐 15개가 몰려도 버킷에 남은 토큰으로 흡수하되,
 * 지속적으로 초과하면 차단된다. 실사용 패턴에 자연스럽게 맞는 알고리즘이다.</p>
 *
 * <h3>왜 외부 라이브러리(Bucket4j) 없이 직접 구현했는가?</h3>
 * <ul>
 *   <li>의존성 최소화: 단일 인스턴스 배포에서 Caffeine만으로 충분하다</li>
 *   <li>면접 어필: 알고리즘 이해도를 보여주는 것이 라이브러리 사용보다 효과적</li>
 *   <li>Bucket4j 도입은 Redis 분산 락 + 다중 인스턴스 확장 시 적절하다</li>
 * </ul>
 *
 * <h3>동시성 처리</h3>
 * <p>{@code AtomicLong}을 사용하여 CAS(Compare-And-Swap) 기반 lock-free 구현.
 * 여러 스레드가 동시에 {@link #tryConsume()}을 호출해도 원자적으로 처리된다.
 * 토큰 수와 타임스탬프를 단일 long(상위 32비트 = 토큰 수, 하위 32비트 = 시간)으로
 * 인코딩하여 하나의 CAS 연산으로 두 값을 동시에 갱신한다.</p>
 *
 * @see RateLimitPlan — 엔드포인트별 버킷 용량/리필 속도 정의
 * @see RateLimitService — 사용자/IP별 버킷 관리
 */
public class TokenBucket {

    private final int capacity;
    private final int refillTokens;
    private final long refillIntervalMillis;

    /**
     * 상태를 단일 AtomicLong으로 인코딩하여 lock-free CAS를 달성한다.
     * 상위 32비트: 현재 토큰 수, 하위 32비트: 마지막 리필 시각 (epoch초 % 2^32)
     *
     * 이 기법은 java.util.concurrent의 AbstractQueuedSynchronizer가
     * state 필드에 reader count와 writer flag를 함께 인코딩하는 것과 동일한 원리이다.
     */
    private final AtomicLong state;

    /**
     * @param capacity            버킷 최대 토큰 수 (순간 버스트 허용량)
     * @param refillTokens        리필 주기마다 충전되는 토큰 수
     * @param refillIntervalMillis 리필 주기 (밀리초)
     */
    public TokenBucket(int capacity, int refillTokens, long refillIntervalMillis) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillIntervalMillis = refillIntervalMillis;
        // 초기 상태: 버킷 가득 참 + 현재 시각
        this.state = new AtomicLong(encode(capacity, nowSeconds()));
    }

    /**
     * 토큰 1개 소비를 시도한다.
     *
     * <p>CAS 루프로 원자적으로 처리한다:</p>
     * <ol>
     *   <li>현재 상태 읽기 (토큰 수 + 마지막 리필 시각)</li>
     *   <li>경과 시간만큼 토큰 리필 계산</li>
     *   <li>토큰이 1개 이상이면 소비, 아니면 거부</li>
     *   <li>CAS로 상태 갱신 시도 → 실패하면 재시도</li>
     * </ol>
     *
     * @return 소비 결과 (성공 여부 + 잔여 토큰 수)
     */
    public ConsumeResult tryConsume() {
        while (true) {
            long current = state.get();
            int tokens = decodeTokens(current);
            long lastRefillSec = decodeTimestamp(current);
            long nowSec = nowSeconds();

            // 경과 시간에 비례하여 토큰 리필
            long elapsedMillis = (nowSec - lastRefillSec) * 1000;
            if (elapsedMillis >= refillIntervalMillis) {
                long intervals = elapsedMillis / refillIntervalMillis;
                long refilled = intervals * refillTokens;
                tokens = (int) Math.min(capacity, tokens + refilled);
                // 리필된 시간만큼만 갱신하여 잔여 밀리초를 보존
                lastRefillSec += intervals * (refillIntervalMillis / 1000);
            }

            if (tokens <= 0) {
                // 다음 토큰 리필까지 남은 시간 (초)
                long nextRefillMillis = refillIntervalMillis - (elapsedMillis % refillIntervalMillis);
                long retryAfterSec = Math.max(1, (nextRefillMillis + 999) / 1000);
                return new ConsumeResult(false, 0, retryAfterSec);
            }

            int newTokens = tokens - 1;
            long next = encode(newTokens, lastRefillSec);
            if (state.compareAndSet(current, next)) {
                return new ConsumeResult(true, newTokens, 0);
            }
            // CAS 실패 → 다른 스레드가 먼저 갱신함 → 재시도
        }
    }

    /**
     * 현재 잔여 토큰 수를 반환한다 (리필 반영).
     * 모니터링/테스트용이며, 이 값은 호출 직후 변경될 수 있다.
     */
    public int getAvailableTokens() {
        long current = state.get();
        int tokens = decodeTokens(current);
        long lastRefillSec = decodeTimestamp(current);
        long nowSec = nowSeconds();

        long elapsedMillis = (nowSec - lastRefillSec) * 1000;
        if (elapsedMillis >= refillIntervalMillis) {
            long intervals = elapsedMillis / refillIntervalMillis;
            long refilled = intervals * refillTokens;
            tokens = (int) Math.min(capacity, tokens + refilled);
        }
        return tokens;
    }

    public int getCapacity() {
        return capacity;
    }

    // ── 인코딩/디코딩 ──────────────────────────────────

    /**
     * 토큰 수(상위 32비트)와 타임스탬프(하위 32비트)를 하나의 long으로 인코딩한다.
     * 이 기법으로 두 값을 하나의 CAS 연산으로 원자적으로 갱신할 수 있다.
     */
    private static long encode(int tokens, long timestampSeconds) {
        return ((long) tokens << 32) | (timestampSeconds & 0xFFFFFFFFL);
    }

    private static int decodeTokens(long encoded) {
        return (int) (encoded >>> 32);
    }

    private static long decodeTimestamp(long encoded) {
        return encoded & 0xFFFFFFFFL;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    /**
     * 토큰 소비 결과.
     *
     * @param allowed        소비 허용 여부
     * @param remainingTokens 소비 후 잔여 토큰 수 (거부 시 0)
     * @param retryAfterSec  거부 시 재시도까지 대기 시간 (초). 허용 시 0
     */
    public record ConsumeResult(boolean allowed, int remainingTokens, long retryAfterSec) {
    }
}

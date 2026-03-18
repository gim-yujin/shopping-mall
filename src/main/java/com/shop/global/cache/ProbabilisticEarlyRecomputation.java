package com.shop.global.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import org.checkerframework.checker.index.qual.NonNegative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * [Phase 16] 확률적 조기 재계산(PER, Probabilistic Early Recomputation) 기반 캐시 만료 정책.
 *
 * <h3>Thundering Herd의 남은 문제</h3>
 * <p>Phase 10에서 {@code @Cacheable(sync=true)}를 적용하여 동일 키에 대한 동시 DB 조회를
 * 1회로 제한했다. 하지만 sync=true는 <b>캐시 미스가 이미 발생한 시점</b>에서만 작동한다.
 * 캐시 엔트리가 TTL에 의해 하드 만료되면:</p>
 * <ol>
 *   <li>첫 번째 요청이 캐시 미스를 감지하고 DB 조회를 시작한다</li>
 *   <li>DB 쿼리가 완료되기 전까지 나머지 N-1개 요청이 <b>블로킹 대기</b>한다</li>
 *   <li>인기 상품(hot key)의 경우 수십~수백 스레드가 대기하여 <b>latency spike</b>가 발생한다</li>
 * </ol>
 * <p>이 지연은 특히 1분 TTL 캐시(bestSellers, topCategories)에서 매분 발생한다.</p>
 *
 * <h3>PER 알고리즘으로 해결</h3>
 * <p>"Optimal Probabilistic Cache Stampede Prevention" (Vattani et al.) 논문 기반.
 * 캐시가 실제로 만료되기 <b>전에</b> 확률적으로 조기 만료를 유도하여,
 * 트래픽이 많은 키일수록 만료 시점이 분산되고, 하드 만료 전에 자연스럽게 갱신된다.</p>
 *
 * <h3>XFetch 공식</h3>
 * <pre>
 *   currentTime − (TTL × β × ln(random())) > expireTime
 *   → true이면 이 요청에서 캐시를 조기 만료시킨다
 * </pre>
 * <ul>
 *   <li><b>β (beta)</b>: 조기 만료 확률을 제어하는 계수. 값이 클수록 더 일찍 만료.
 *       기본값 1.0이면 TTL의 약 마지막 10~20% 구간에서 점진적으로 만료 확률이 증가한다.</li>
 *   <li><b>ln(random())</b>: (0,1] 균등 분포의 로그. 대부분 음수이므로 빼면 양수가 된다.
 *       드물게 매우 큰 값이 나와 TTL 초반에도 만료가 발생하지만 확률이 극히 낮다.</li>
 * </ul>
 *
 * <h3>효과</h3>
 * <ul>
 *   <li>트래픽이 많은 키: TTL 만료 전에 높은 확률로 갱신됨 → 하드 만료가 거의 발생하지 않음</li>
 *   <li>트래픽이 적은 키: 어차피 요청이 드물어 PER 효과가 미미하지만, 스탬피드 위험도 낮음</li>
 *   <li>sync=true와 함께 사용하면 PER이 놓친 희귀한 하드 만료도 안전하게 처리됨</li>
 * </ul>
 */
public class ProbabilisticEarlyRecomputation implements Expiry<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(ProbabilisticEarlyRecomputation.class);

    private final long ttlNanos;
    private final double beta;
    private final LongAdder earlyRefreshCount = new LongAdder();

    /**
     * @param ttl      캐시 TTL 값
     * @param ttlUnit  TTL 시간 단위
     * @param beta     조기 만료 계수 (1.0 권장). 값이 클수록 더 일찍 갱신 시도.
     */
    public ProbabilisticEarlyRecomputation(long ttl, TimeUnit ttlUnit, double beta) {
        this.ttlNanos = ttlUnit.toNanos(ttl);
        this.beta = beta;
    }

    /**
     * 엔트리 최초 생성 시 호출. 표준 TTL을 그대로 적용한다.
     */
    @Override
    public long expireAfterCreate(Object key, Object value, long currentTime) {
        return ttlNanos;
    }

    /**
     * 엔트리 갱신 시 호출. TTL을 리셋한다.
     */
    @Override
    public long expireAfterUpdate(Object key, Object value, long currentTime,
                                  @NonNegative long currentDuration) {
        return ttlNanos;
    }

    /**
     * 엔트리 읽기 시 호출 — PER 핵심 로직.
     *
     * <p>남은 TTL이 충분하면 현재 만료 시간을 유지한다.
     * TTL 후반부에 진입하면 확률적으로 만료 시간을 0으로 반환하여
     * 다음 접근 시 캐시 미스를 유발, @Cacheable(sync=true)에 의해
     * 단일 스레드가 DB를 재조회한다.</p>
     *
     * <p>XFetch 조건: {@code elapsedNanos + ttlNanos * beta * (-ln(random)) > ttlNanos}
     * → 간소화: {@code -ttlNanos * beta * ln(random) > remainingNanos}</p>
     */
    @Override
    public long expireAfterRead(Object key, Object value, long currentTime,
                                @NonNegative long currentDuration) {
        // currentDuration = 남은 만료 시간 (나노초)
        // 남은 시간이 TTL의 50% 이상이면 PER 계산을 건너뛴다 (성능 최적화)
        if (currentDuration > ttlNanos / 2) {
            return currentDuration;
        }

        // XFetch: -ttl * beta * ln(random) > remaining → 조기 만료
        double randomFactor = -Math.log(ThreadLocalRandom.current().nextDouble());
        long earlyExpiryThreshold = (long) (ttlNanos * beta * randomFactor);

        if (earlyExpiryThreshold > currentDuration) {
            earlyRefreshCount.increment();
            if (log.isDebugEnabled()) {
                log.debug("PER 조기 만료 유도 - key={}, remaining={}ms, threshold={}ms",
                        key,
                        TimeUnit.NANOSECONDS.toMillis(currentDuration),
                        TimeUnit.NANOSECONDS.toMillis(earlyExpiryThreshold));
            }
            // 즉시 만료 → 다음 접근에서 @Cacheable(sync=true)가 재계산 트리거
            return 0;
        }

        return currentDuration;
    }

    /** [Phase 16] PER에 의한 조기 만료 횟수. 메트릭 노출용. */
    public long getEarlyRefreshCount() {
        return earlyRefreshCount.sum();
    }
}

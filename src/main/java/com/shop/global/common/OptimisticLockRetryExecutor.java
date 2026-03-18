package com.shop.global.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * [Phase 4] 낙관적 잠금 실패 시 자동 재시도를 수행하는 유틸리티.
 *
 * <p><b>문제:</b> 낙관적 잠금(@Version)이 충돌을 감지하면 {@code OptimisticLockException}이
 * 발생하여 트랜잭션이 롤백된다. 사람이 직접 개입하는 관리자 편집에서는 충돌 시
 * "다시 시도해주세요" 메시지를 보여주면 되지만, 시스템 자동화 작업(배치, 스케줄러 등)에서는
 * 재시도 로직이 필요하다.</p>
 *
 * <p><b>해결:</b> {@code OptimisticLockingFailureException} 발생 시 지수 백오프(exponential backoff)와
 * 함께 최대 N회 재시도한다. 각 재시도는 새 트랜잭션에서 DB의 최신 상태를 읽어 작업을 수행하므로,
 * 이전 충돌이 반영된 상태에서 재시도된다.</p>
 *
 * <p><b>비관적 잠금과의 차이:</b> 비관적 잠금은 행을 선점하여 대기(blocking)시키고,
 * 낙관적 잠금 + 재시도는 충돌 시 실패 후 재시도(non-blocking)한다.
 * 경합이 낮은 작업에서는 잠금 대기 없이 처리량이 높아지고,
 * 경합이 높은 작업에서는 재시도 오버헤드가 커서 비관적 잠금이 유리하다.</p>
 *
 * <p><b>사용 예:</b></p>
 * <pre>
 * retryExecutor.executeWithRetry(3, () -> {
 *     couponService.updateCoupon(couponId, request);
 *     return null;
 * });
 * </pre>
 */
@Component
public class OptimisticLockRetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetryExecutor.class);

    /** 기본 재시도 횟수 */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** 초기 백오프 (밀리초) */
    private static final long INITIAL_BACKOFF_MS = 50;

    /**
     * 기본 재시도 횟수(3회)로 작업을 실행한다.
     *
     * @param operation 실행할 작업 (새 트랜잭션 내에서 호출되어야 함)
     * @param <T>       반환 타입
     * @return 작업 결과
     * @throws ObjectOptimisticLockingFailureException 최대 재시도 후에도 충돌이 해결되지 않으면
     */
    public <T> T executeWithRetry(Supplier<T> operation) {
        return executeWithRetry(DEFAULT_MAX_RETRIES, operation);
    }

    /**
     * 지정된 횟수만큼 재시도하며 작업을 실행한다.
     *
     * <p>재시도 간격은 지수 백오프를 적용한다: 50ms → 100ms → 200ms → ...
     * 이는 동시 재시도들이 같은 시점에 다시 충돌하는 것(thundering herd)을 방지한다.</p>
     *
     * @param maxRetries 최대 재시도 횟수 (0이면 재시도 없음)
     * @param operation  실행할 작업
     * @param <T>        반환 타입
     * @return 작업 결과
     * @throws ObjectOptimisticLockingFailureException 최대 재시도 후에도 충돌이 해결되지 않으면
     */
    public <T> T executeWithRetry(int maxRetries, Supplier<T> operation) {
        int attempt = 0;

        while (true) {
            try {
                return operation.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                attempt++;
                if (attempt > maxRetries) {
                    log.warn("낙관적 잠금 재시도 한도 초과 - 최대 {}회 시도 후 실패. entity={}",
                            maxRetries, e.getPersistentClassName());
                    throw e;
                }

                long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                log.info("낙관적 잠금 충돌 감지 - 재시도 {}/{}, 대기 {}ms. entity={}",
                        attempt, maxRetries, backoff, e.getPersistentClassName());

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}

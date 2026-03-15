package com.shop.global.idempotency;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 멱등성 레코드 배치 삭제 실행기.
 *
 * <p>Spring AOP는 프록시 기반이므로, 같은 클래스 내에서 this.deleteBatch()를 호출하면
 * {@code @Transactional} 어노테이션이 적용되지 않는다 (self-invocation 문제).
 * 배치별 독립 트랜잭션을 보장하기 위해 실제 삭제 로직을 별도 빈으로 분리하여
 * Spring 프록시를 통한 정상적인 트랜잭션 경계를 확보한다.</p>
 *
 * <p>이 패턴은 {@code SearchLogCleanupExecutor}와 동일하다.</p>
 */
@Component
public class IdempotencyCleanupExecutor {

    private final IdempotencyRecordRepository repository;

    public IdempotencyCleanupExecutor(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 보존 기간이 지난 레코드를 배치 단위로 삭제한다.
     *
     * @param cutoffDate 이 시점 이전에 생성된 레코드를 삭제
     * @param batchSize  한 번에 삭제할 최대 행 수
     * @return 삭제된 행 수
     */
    @Transactional
    public int deleteBatch(LocalDateTime cutoffDate, int batchSize) {
        return repository.deleteBatchOlderThan(cutoffDate, batchSize);
    }
}

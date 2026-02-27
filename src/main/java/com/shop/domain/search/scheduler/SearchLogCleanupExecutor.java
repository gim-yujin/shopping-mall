package com.shop.domain.search.scheduler;

import com.shop.domain.search.repository.SearchLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * [BUG FIX] 검색 로그 배치 삭제의 트랜잭션 실행을 담당하는 컴포넌트.
 *
 * SearchLogCleanupScheduler에서 분리된 이유:
 * Spring AOP는 프록시 기반으로 동작하므로, 같은 클래스 내에서 this.method()를 호출하면
 * @Transactional 어노테이션이 무시된다 (self-invocation 문제).
 * 배치별 독립 트랜잭션(REQUIRES_NEW)을 보장하기 위해 별도 빈으로 분리하여
 * Scheduler → Executor 호출 시 Spring 프록시를 경유하도록 한다.
 *
 * REQUIRES_NEW를 사용하는 이유:
 * 호출자(Scheduler)에 이미 트랜잭션이 열려 있을 경우에도
 * 각 배치가 독립적으로 커밋/롤백되어야 한다.
 * 이렇게 해야 N번째 배치에서 실패해도 1~(N-1)번째 배치는 이미 커밋된 상태를 유지하며,
 * 다음 스케줄 실행 시 잔여 데이터만 이어서 삭제할 수 있다.
 */
@Component
public class SearchLogCleanupExecutor {

    private final SearchLogRepository searchLogRepository;

    public SearchLogCleanupExecutor(SearchLogRepository searchLogRepository) {
        this.searchLogRepository = searchLogRepository;
    }

    /**
     * 단일 배치 삭제를 독립 트랜잭션으로 실행한다.
     *
     * @param cutoffDate 이 날짜 이전의 로그를 삭제
     * @param batchSize  한 번에 삭제할 최대 행 수
     * @return 실제 삭제된 행 수 (batchSize 미만이면 잔여 데이터 없음)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(LocalDateTime cutoffDate, int batchSize) {
        return searchLogRepository.deleteBatchOlderThan(cutoffDate, batchSize);
    }
}

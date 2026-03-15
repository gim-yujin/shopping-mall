package com.shop.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 이벤트 리포지토리.
 *
 * <p>폴러가 PENDING 이벤트를 순서대로 가져오고,
 * 정리 스케줄러가 오래된 PROCESSED 이벤트를 배치 삭제한다.</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 상태의 이벤트를 생성 순서대로 조회한다.
     *
     * <p>폴러가 호출하며, 한 번에 최대 batchSize건만 가져온다.
     * partial index {@code idx_outbox_pending}을 사용하여
     * PROCESSED/FAILED 레코드는 스캔하지 않는다.</p>
     *
     * <p>LIMIT 절로 한 번에 처리하는 이벤트 수를 제한하여
     * 폴링 주기당 DB 부하를 통제한다.</p>
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "ORDER BY created_at ASC LIMIT :batchSize",
            nativeQuery = true)
    List<OutboxEvent> findPendingEvents(@Param("batchSize") int batchSize);

    /**
     * 처리 완료된 오래된 이벤트를 배치 삭제한다.
     *
     * <p>PROCESSED 상태이면서 처리 시각이 cutoffDate 이전인 레코드를 삭제한다.
     * partial index {@code idx_outbox_processed_at}이 효율적인 삭제를 보장한다.</p>
     *
     * @param cutoffDate 이 시점 이전에 처리된 이벤트를 삭제
     * @param batchSize  한 번에 삭제할 최대 행 수
     * @return 삭제된 행 수
     */
    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE event_id IN ("
            + "SELECT event_id FROM outbox_events "
            + "WHERE status = 'PROCESSED' AND processed_at < :cutoffDate "
            + "LIMIT :batchSize"
            + ")", nativeQuery = true)
    int deleteBatchProcessedBefore(@Param("cutoffDate") LocalDateTime cutoffDate,
                                    @Param("batchSize") int batchSize);
}

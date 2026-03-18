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
     * [Phase 6] PENDING 이벤트를 잠금과 함께 조회한다 (FOR UPDATE SKIP LOCKED).
     *
     * <p><b>문제:</b> 기존 findPendingEvents는 잠금 없이 조회하므로,
     * 다중 인스턴스 배포 시 두 폴러가 동일한 이벤트를 동시에 읽어
     * 중복 처리할 수 있다. at-least-once이므로 정확성은 보장되지만,
     * 불필요한 중복 작업이 발생한다.</p>
     *
     * <p><b>해결:</b> FOR UPDATE SKIP LOCKED를 추가하여 한 폴러가 잠근 이벤트를
     * 다른 폴러가 건너뛰도록 한다. 단일 인스턴스에서도 폴링 주기 겹침에 의한
     * 중복 처리를 방지할 수 있다.</p>
     *
     * <p><b>SKIP LOCKED vs NOWAIT:</b> NOWAIT는 잠금 실패 시 즉시 에러를 반환하지만,
     * SKIP LOCKED는 잠긴 행을 건너뛰고 잠금 가능한 행만 반환한다.
     * 폴러는 "가능한 만큼 처리"하는 것이 목적이므로 SKIP LOCKED가 적합하다.</p>
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "ORDER BY created_at ASC LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findPendingEventsForUpdate(@Param("batchSize") int batchSize);

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

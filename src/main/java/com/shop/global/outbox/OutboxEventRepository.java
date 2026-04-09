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
 * 정리 스케줄러가 오래된 PROCESSED/DEAD_LETTER 이벤트를 배치 삭제한다.</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 상태의 이벤트를 생성 순서대로 조회한다.
     *
     * <p>폴러가 호출하며, 한 번에 최대 batchSize건만 가져온다.
     * partial index {@code idx_outbox_pending}을 사용하여
     * PROCESSED/DEAD_LETTER(및 레거시 FAILED) 레코드는 스캔하지 않는다.</p>
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
     * <p>[Phase 15] next_retry_at 조건 추가: 지수 백오프 대기 중인 이벤트를
     * 건너뛰고, 즉시 처리 가능한 이벤트만 조회한다.
     * next_retry_at이 NULL이면 최초 시도이므로 즉시 처리한다.</p>
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "AND (next_retry_at IS NULL OR next_retry_at <= NOW()) "
            + "ORDER BY created_at ASC LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findPendingEventsForUpdate(@Param("batchSize") int batchSize);

    /**
     * 최초 시도(next_retry_at이 NULL) PENDING 이벤트를 잠금과 함께 조회한다.
     *
     * <p>Retry Budget 패턴에서 신규 이벤트와 재시도 이벤트를 분리 조회하여,
     * 재시도 폭증 시에도 신규 이벤트 처리가 지연되지 않도록 한다.</p>
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "AND next_retry_at IS NULL "
            + "ORDER BY created_at ASC LIMIT :batchSize "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findFirstAttemptEventsForUpdate(@Param("batchSize") int batchSize);

    /**
     * 재시도 대상(next_retry_at이 현재 시각 이하) PENDING 이벤트를 잠금과 함께 조회한다.
     *
     * <p>Retry Budget으로 조회량을 제한하여, 대량 재시도가 한 폴링 주기에
     * 몰려도 폴러 과부하와 외부 서비스 부하 스파이크를 방지한다.</p>
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' "
            + "AND next_retry_at IS NOT NULL AND next_retry_at <= NOW() "
            + "ORDER BY next_retry_at ASC LIMIT :budgetSize "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findRetryEventsForUpdate(@Param("budgetSize") int budgetSize);

    /**
     * [Phase 15] DEAD_LETTER 상태의 이벤트를 최신순으로 조회한다.
     *
     * <p>관리자가 실패 원인(lastError)을 확인하고, 장애 해소 후
     * requeueFromDeadLetter()로 PENDING 상태로 되돌릴 이벤트를 선택하기 위해 사용한다.</p>
     */
    @Query(value = "SELECT * FROM outbox_events WHERE status = 'DEAD_LETTER' "
            + "ORDER BY processed_at DESC LIMIT :limit",
            nativeQuery = true)
    List<OutboxEvent> findDeadLetterEvents(@Param("limit") int limit);

    /**
     * [Phase 15] 특정 상태의 이벤트 수를 조회한다.
     *
     * <p>OutboxMetrics에서 PENDING/DEAD_LETTER 큐 깊이를 Gauge로 노출하기 위해 사용한다.
     * Prometheus가 15초 간격으로 스크래핑할 때마다 호출되므로,
     * 단순 COUNT 쿼리로 부하를 최소화한다.</p>
     */
    long countByStatus(String status);

    /**
     * [Phase 15] 보존 기간을 초과한 DEAD_LETTER 이벤트를 배치 삭제한다.
     *
     * <p>DEAD_LETTER 이벤트는 PROCESSED보다 긴 보존 기간(기본 30일)을 적용한다.
     * 장기간 미처리된 Dead Letter는 원인 분석 후 삭제 대상이 된다.</p>
     */
    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE event_id IN ("
            + "SELECT event_id FROM outbox_events "
            + "WHERE status = 'DEAD_LETTER' AND processed_at < :cutoffDate "
            + "LIMIT :batchSize"
            + ")", nativeQuery = true)
    int deleteBatchDeadLetterBefore(@Param("cutoffDate") LocalDateTime cutoffDate,
                                     @Param("batchSize") int batchSize);

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

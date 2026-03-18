package com.shop.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 멱등성 레코드 리포지토리.
 *
 * <p>UNIQUE 인덱스 uk_idempotency_user_key (user_id, idempotency_key)가
 * 동시 중복 삽입을 물리적으로 차단한다. findByUserIdAndIdempotencyKey 조회는
 * 정상 흐름 최적화용이며, 진정한 방어선은 INSERT 시 발생하는
 * DataIntegrityViolationException이다.</p>
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    /**
     * 사용자 ID와 멱등성 키 조합으로 기존 레코드를 조회한다.
     *
     * <p>조회 결과에 따라 세 가지 분기가 발생한다:</p>
     * <ul>
     *   <li>empty → 최초 요청, PROCESSING 레코드를 INSERT</li>
     *   <li>COMPLETED → 중복 요청, 캐시된 응답 반환</li>
     *   <li>PROCESSING → 이전 요청 처리 중, 409 Conflict 반환</li>
     * </ul>
     */
    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * FAILED 상태의 레코드를 삭제하여 동일 키로 재시도를 허용한다.
     *
     * <p>주문 생성 중 예외가 발생하면 레코드를 FAILED로 전환한 뒤,
     * 클라이언트가 같은 키로 재요청하면 이 메서드로 FAILED 레코드를 삭제하고
     * 새로운 PROCESSING 레코드를 삽입하여 재처리한다.</p>
     */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.userId = :userId AND r.idempotencyKey = :key AND r.status = 'FAILED'")
    int deleteFailedRecord(@Param("userId") Long userId, @Param("key") String key);

    /**
     * [Phase 14] PROCESSING 상태에서 지정 시간 이상 고착된 레코드를 FAILED로 일괄 전환한다.
     *
     * <p>서버 크래시, JVM OOM 등으로 markCompleted()/markFailed()가 호출되지 못하면
     * PROCESSING 레코드가 영구 고착되어 클라이언트는 409 Conflict을 무한 수신한다.
     * 이 쿼리는 cutoffTime 이전에 생성된 PROCESSING 레코드를 FAILED로 전환하여
     * 클라이언트가 같은 키로 재시도할 수 있도록 복구한다.</p>
     *
     * @param cutoffTime 이 시점 이전에 생성된 PROCESSING 레코드를 대상으로 한다
     * @return 전환된 행 수
     */
    @Modifying
    @Query("UPDATE IdempotencyRecord r SET r.status = 'FAILED', r.completedAt = CURRENT_TIMESTAMP "
            + "WHERE r.status = 'PROCESSING' AND r.createdAt < :cutoffTime")
    int recoverStaleProcessingRecords(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 보존 기간이 지난 레코드를 배치 단위로 삭제한다.
     *
     * <p>{@link IdempotencyCleanupScheduler}에서 호출하며,
     * LIMIT 절로 한 번에 삭제하는 행 수를 제한하여 WAL 크기와 잠금 시간을 분산한다.</p>
     *
     * @param cutoffDate 이 시점 이전에 생성된 레코드를 삭제 대상으로 한다
     * @param batchSize  한 번에 삭제할 최대 행 수
     * @return 삭제된 행 수
     */
    @Modifying
    @Query(value = "DELETE FROM idempotency_records WHERE record_id IN ("
            + "SELECT record_id FROM idempotency_records "
            + "WHERE created_at < :cutoffDate LIMIT :batchSize"
            + ")", nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoffDate") LocalDateTime cutoffDate,
                             @Param("batchSize") int batchSize);
}

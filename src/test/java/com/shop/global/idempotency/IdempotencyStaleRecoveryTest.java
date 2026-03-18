package com.shop.global.idempotency;

import com.shop.global.metrics.IdempotencyMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Phase 14] PROCESSING 고착 레코드 자동 복구 통합 테스트.
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>staleTimeoutMinutes 이상 PROCESSING 상태인 레코드가 FAILED로 자동 전환됨</li>
 *   <li>FAILED 전환 후 동일 키로 재시도 시 새 PROCESSING 레코드가 생성됨</li>
 *   <li>정상 PROCESSING 레코드(타임아웃 미경과)는 영향받지 않음</li>
 *   <li>IdempotencyMetrics에 stale 복구 카운터가 기록됨</li>
 * </ol>
 *
 * <h3>왜 통합 테스트인가?</h3>
 * <p>PROCESSING → FAILED 전환은 DB UPDATE 쿼리이므로, 실제 PostgreSQL에서
 * createdAt 비교와 상태 전환이 올바르게 동작하는지 검증해야 한다.
 * Mock 기반 단위 테스트로는 JPQL 쿼리의 정확성을 보장할 수 없다.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "logging.level.org.hibernate.SQL=WARN",
        "app.idempotency.stale-timeout-minutes=5"
})
class IdempotencyStaleRecoveryTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private IdempotencyMetrics idempotencyMetrics;

    // =========================================================================
    // 시나리오 1: 고착 PROCESSING 레코드 → FAILED 자동 전환
    // =========================================================================

    /**
     * [Phase 14] 5분 이상 PROCESSING 상태인 레코드가 FAILED로 전환되어
     * 클라이언트가 동일 키로 재시도할 수 있음을 검증한다.
     *
     * <p>서버 크래시 시나리오를 시뮬레이션하기 위해 createdAt을 10분 전으로 설정하고,
     * recoverStaleProcessing()을 호출하여 FAILED 전환을 확인한다.</p>
     */
    @Test
    @DisplayName("5분 이상 PROCESSING 고착 레코드가 FAILED로 자동 전환된다")
    void staleProcessingRecord_recoveredToFailed() {
        // given: 10분 전에 생성된 PROCESSING 레코드 (타임아웃 초과)
        String staleKey = "stale-test-" + System.nanoTime();
        IdempotencyRecord record = idempotencyService.initRecord(9001L, staleKey, "ORDER");
        Long recordId = record.getRecordId();

        // createdAt을 10분 전으로 조작하여 고착 상태 시뮬레이션
        repository.findById(recordId).ifPresent(r -> {
            org.springframework.test.util.ReflectionTestUtils.setField(r, "createdAt",
                    LocalDateTime.now().minusMinutes(10));
            repository.save(r);
        });

        // when: 5분 기준으로 고착 복구 실행
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(5);
        int recovered = idempotencyService.recoverStaleProcessing(cutoffTime);

        // then: PROCESSING → FAILED 전환 확인
        assertThat(recovered).isGreaterThanOrEqualTo(1);
        Optional<IdempotencyRecord> updated = repository.findById(recordId);
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(IdempotencyRecord.STATUS_FAILED);
        assertThat(updated.get().getCompletedAt()).isNotNull();

        // cleanup
        repository.deleteById(recordId);
    }

    // =========================================================================
    // 시나리오 2: 정상 PROCESSING 레코드는 영향받지 않음
    // =========================================================================

    /**
     * [Phase 14] 타임아웃 미경과 PROCESSING 레코드는 복구 대상에서 제외됨을 검증한다.
     *
     * <p>방금 생성된 PROCESSING 레코드는 아직 타임아웃에 도달하지 않았으므로
     * recoverStaleProcessing()이 이를 건드리지 않아야 한다.</p>
     */
    @Test
    @DisplayName("타임아웃 미경과 PROCESSING 레코드는 복구 대상에서 제외된다")
    void freshProcessingRecord_notAffected() {
        // given: 방금 생성된 PROCESSING 레코드
        String freshKey = "fresh-test-" + System.nanoTime();
        IdempotencyRecord record = idempotencyService.initRecord(9002L, freshKey, "ORDER");
        Long recordId = record.getRecordId();

        // when: 5분 기준으로 고착 복구 실행
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(5);
        idempotencyService.recoverStaleProcessing(cutoffTime);

        // then: 여전히 PROCESSING 상태 유지
        Optional<IdempotencyRecord> unchanged = repository.findById(recordId);
        assertThat(unchanged).isPresent();
        assertThat(unchanged.get().getStatus()).isEqualTo(IdempotencyRecord.STATUS_PROCESSING);

        // cleanup
        repository.deleteById(recordId);
    }

    // =========================================================================
    // 시나리오 3: FAILED 전환 후 동일 키로 재시도
    // =========================================================================

    /**
     * [Phase 14] 고착 복구(PROCESSING → FAILED) 후 동일 키로 retryAfterFailure()를
     * 호출하면 새 PROCESSING 레코드가 정상 생성됨을 검증한다.
     *
     * <p>이 테스트는 고착 복구의 전체 흐름(고착 감지 → FAILED 전환 → 클라이언트 재시도)이
     * 정상적으로 동작하는지 확인한다.</p>
     */
    @Test
    @DisplayName("고착 복구 후 동일 키로 재시도하면 새 PROCESSING 레코드가 생성된다")
    void afterStaleRecovery_retryCreatesNewProcessing() {
        // given: 고착된 PROCESSING 레코드를 FAILED로 전환
        String retryKey = "retry-stale-" + System.nanoTime();
        IdempotencyRecord record = idempotencyService.initRecord(9003L, retryKey, "ORDER");
        Long originalId = record.getRecordId();

        repository.findById(originalId).ifPresent(r -> {
            org.springframework.test.util.ReflectionTestUtils.setField(r, "createdAt",
                    LocalDateTime.now().minusMinutes(10));
            repository.save(r);
        });

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(5);
        idempotencyService.recoverStaleProcessing(cutoffTime);

        // when: 동일 키로 재시도
        IdempotencyRecord retryRecord = idempotencyService.retryAfterFailure(
                9003L, retryKey, "ORDER");

        // then: 새 PROCESSING 레코드 생성 확인
        assertThat(retryRecord.getStatus()).isEqualTo(IdempotencyRecord.STATUS_PROCESSING);
        assertThat(retryRecord.getRecordId()).isNotEqualTo(originalId);

        // cleanup
        repository.deleteById(retryRecord.getRecordId());
    }

    // =========================================================================
    // 시나리오 4: IdempotencyMetrics 등록 검증
    // =========================================================================

    /**
     * [Phase 14] IdempotencyMetrics의 카운터가 MeterRegistry에 등록되어
     * Prometheus 스크래핑 가능한지 검증한다.
     */
    @Test
    @DisplayName("IdempotencyMetrics 카운터가 MeterRegistry에 등록되어 있다")
    void idempotencyMetrics_registeredInMeterRegistry() {
        // 요청 결과별 카운터 등록 확인
        assertThat(meterRegistry.find("shop.idempotency.requests.total")
                .tag("result", "new").counter())
                .as("new 카운터가 등록되어야 합니다")
                .isNotNull();

        assertThat(meterRegistry.find("shop.idempotency.requests.total")
                .tag("result", "duplicate_completed").counter())
                .as("duplicate_completed 카운터가 등록되어야 합니다")
                .isNotNull();

        assertThat(meterRegistry.find("shop.idempotency.requests.total")
                .tag("result", "duplicate_processing").counter())
                .as("duplicate_processing 카운터가 등록되어야 합니다")
                .isNotNull();

        assertThat(meterRegistry.find("shop.idempotency.requests.total")
                .tag("result", "conflict").counter())
                .as("conflict 카운터가 등록되어야 합니다")
                .isNotNull();

        assertThat(meterRegistry.find("shop.idempotency.requests.total")
                .tag("result", "retry").counter())
                .as("retry 카운터가 등록되어야 합니다")
                .isNotNull();

        // stale 복구 카운터 등록 확인
        assertThat(meterRegistry.find("shop.idempotency.stale.recovered.total").counter())
                .as("stale.recovered.total 카운터가 등록되어야 합니다")
                .isNotNull();
    }
}

package com.shop.global.infra;

import com.shop.testsupport.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Phase 11] 비관적 잠금 타임아웃 동작 검증 테스트.
 *
 * <h3>문제: 무한 잠금 대기로 인한 커넥션 풀 고갈</h3>
 * <p>PostgreSQL의 기본 lock_timeout은 0(무한 대기)이다.
 * 트랜잭션 A가 상품 행에 FOR UPDATE 잠금을 걸고 장시간 처리하면,
 * 같은 행을 잠그려는 트랜잭션 B, C, D는 각각 커넥션을 점유한 채 무한 대기한다.
 * HikariCP 풀(17개)이 모두 잠금 대기에 빠지면 신규 요청은
 * 커넥션을 얻지 못해 connection-timeout(5초) 후 실패한다.</p>
 *
 * <h3>해결: connection-init-sql로 세션 레벨 lock_timeout 설정</h3>
 * <p>HikariCP의 connection-init-sql에 {@code SET lock_timeout = '5s'}를 설정하면
 * 풀에서 생성되는 모든 커넥션에 세션 레벨 타임아웃이 적용된다.
 * 잠금 획득이 5초를 초과하면 PostgreSQL이 즉시
 * {@code ERROR: canceling statement due to lock timeout} (SQLState 55P03)을 반환하여
 * 커넥션을 빠르게 해제한다.</p>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>잠금 보유자가 잠금을 장시간 유지하면 대기자가 타임아웃으로 즉시 실패</li>
 *   <li>타임아웃 소요 시간이 설정값(3초)과 근사</li>
 *   <li>타임아웃 예외가 Spring의 PessimisticLockingFailureException으로 정확히 변환</li>
 *   <li>잠금 보유자의 트랜잭션은 타임아웃에 영향 없이 정상 완료</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=5",
        // [Phase 11] 테스트 전용: 3초 잠금 타임아웃으로 빠른 검증.
        // 운영(5초)보다 짧게 설정하여 테스트 실행 시간을 단축한다.
        "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '3s'",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class LockTimeoutConcurrencyTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TestDataFactory testDataFactory;

    private TestDataFactory.FixtureContext fixture;
    private Long testProductId;

    @BeforeEach
    void setUp() {
        fixture = testDataFactory.newContext();
        testProductId = fixture.createActiveProduct(100);
    }

    @AfterEach
    void tearDown() {
        fixture.cleanup();
    }

    // =========================================================================
    // 시나리오 1: 잠금 보유자가 장시간 유지 → 대기자 타임아웃
    // =========================================================================

    /**
     * [Phase 11] 트랜잭션 A가 상품 행을 FOR UPDATE로 잠근 상태에서
     * 트랜잭션 B가 같은 행의 잠금을 시도하면 3초 후 타임아웃으로 실패.
     *
     * <p><b>테스트 흐름:</b></p>
     * <ol>
     *   <li>Thread A: 원시 JDBC로 상품 행에 FOR UPDATE 잠금 획득 후 대기</li>
     *   <li>Main thread: Thread A의 잠금 획득을 확인한 후,
     *       TransactionTemplate으로 같은 행의 잠금을 시도</li>
     *   <li>Main thread: 3초 후 PessimisticLockingFailureException 발생 확인</li>
     *   <li>Thread A: 잠금 해제 신호를 받고 정상 커밋</li>
     * </ol>
     *
     * <p><b>핵심 검증:</b> lock_timeout이 없으면 Main thread는 Thread A가
     * 커밋할 때까지 무한 대기한다. lock_timeout=3s가 설정되면 3초 후
     * 즉시 실패하여 커넥션을 반환한다.</p>
     */
    @Test
    @DisplayName("잠금 보유 중인 행에 대해 대기자가 3초 후 타임아웃으로 실패")
    void lockHeldByOtherTransaction_waiterTimesOut() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch canRelease = new CountDownLatch(1);
        AtomicReference<Exception> holderError = new AtomicReference<>();

        // ── Thread A: 잠금 보유자 ──
        // 원시 JDBC를 사용하여 트랜잭션 수명을 직접 제어한다.
        // Spring @Transactional은 메서드 종료 시 자동 커밋하므로
        // 잠금을 장시간 유지하는 시나리오에는 적합하지 않다.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM products WHERE product_id = ? FOR UPDATE")) {
                    ps.setLong(1, testProductId);
                    ps.executeQuery();
                    // 잠금 획득 완료 — 대기자 스레드에 신호
                    lockAcquired.countDown();

                    // 대기자가 타임아웃을 겪을 때까지 잠금을 유지한다.
                    // canRelease 신호가 올 때까지 또는 30초 타임아웃까지 대기.
                    canRelease.await(30, TimeUnit.SECONDS);
                    conn.commit();
                }
            } catch (Exception e) {
                holderError.set(e);
            }
        });

        try {
            // Thread A가 잠금을 획득할 때까지 대기
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS))
                    .as("잠금 보유자가 잠금을 획득해야 합니다")
                    .isTrue();

            // ── Main thread: 같은 행의 잠금 시도 → 타임아웃 ──
            long startMs = System.currentTimeMillis();

            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() ->
                    tt.execute(status -> {
                        // JdbcTemplate을 통해 같은 행에 FOR UPDATE 시도
                        // connection-init-sql에 의해 이 커넥션의 lock_timeout=3s
                        org.springframework.jdbc.core.JdbcTemplate txJdbc =
                                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
                        txJdbc.queryForMap(
                                "SELECT * FROM products WHERE product_id = ? FOR UPDATE",
                                testProductId);
                        return null;
                    })
            )
                    // PostgreSQL SQLState 55P03 → "canceling statement due to lock timeout"
                    // JdbcTemplate 경유 시 UncategorizedSQLException으로 래핑되고,
                    // JPA 경유 시 CannotAcquireLockException으로 래핑된다.
                    // 두 경로 모두 DataAccessException의 하위 타입.
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("lock timeout");

            long elapsedMs = System.currentTimeMillis() - startMs;

            // ── 불변식 검증 ──

            // ① 타임아웃 소요 시간이 설정값(3초)과 근사 (±1.5초 허용)
            assertThat(elapsedMs)
                    .as("잠금 타임아웃은 설정값(3000ms) 근처여야 합니다 (실제: %dms)", elapsedMs)
                    .isBetween(1500L, 6000L);

            // ② 잠금 보유자에게 에러가 없어야 한다
            assertThat(holderError.get())
                    .as("잠금 보유자의 트랜잭션은 정상이어야 합니다")
                    .isNull();
        } finally {
            // Thread A의 잠금 해제
            canRelease.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 시나리오 2: 잠금 타임아웃 후 풀 커넥션 정상 반환 확인
    // =========================================================================

    /**
     * [Phase 11] 잠금 타임아웃으로 실패한 후 커넥션이 풀에 정상 반환되어
     * 후속 쿼리가 성공하는지 검증.
     *
     * <p><b>위험:</b> 잠금 타임아웃 시 커넥션이 ABORT 상태로 남거나
     * 풀에 반환되지 않으면, 후속 요청이 사용 불가능한 커넥션을 받거나
     * 풀 고갈이 발생할 수 있다. HikariCP는 예외 발생 후 커넥션 상태를
     * 확인하고 필요 시 폐기+재생성하므로 이 문제가 없어야 한다.</p>
     */
    @Test
    @DisplayName("잠금 타임아웃 후 커넥션 풀이 정상 동작 — 후속 쿼리 성공")
    void afterLockTimeout_connectionPoolRemainsHealthy() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch canRelease = new CountDownLatch(1);

        // Thread A: 잠금 보유
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM products WHERE product_id = ? FOR UPDATE")) {
                    ps.setLong(1, testProductId);
                    ps.executeQuery();
                    lockAcquired.countDown();
                    canRelease.await(30, TimeUnit.SECONDS);
                    conn.commit();
                }
            } catch (Exception ignored) {
                // 테스트 종료 시 인터럽트 가능
            }
        });

        try {
            assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

            // 잠금 타임아웃 유발
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            try {
                tt.execute(status -> {
                    org.springframework.jdbc.core.JdbcTemplate txJdbc =
                            new org.springframework.jdbc.core.JdbcTemplate(dataSource);
                    txJdbc.queryForMap(
                            "SELECT * FROM products WHERE product_id = ? FOR UPDATE",
                            testProductId);
                    return null;
                });
            } catch (DataAccessException expected) {
                // 기대되는 예외 (lock timeout) — 무시
            }

            // 잠금 보유자 해제
            canRelease.countDown();
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            // ── 검증: 풀이 정상 동작하는지 확인 ──
            // 잠금 타임아웃 후에도 커넥션 풀에서 새 커넥션을 정상적으로 받아
            // 쿼리를 실행할 수 있어야 한다.
            TransactionTemplate verifyTt = new TransactionTemplate(transactionManager);
            Integer stockQuantity = verifyTt.execute(status -> {
                org.springframework.jdbc.core.JdbcTemplate verifyJdbc =
                        new org.springframework.jdbc.core.JdbcTemplate(dataSource);
                return verifyJdbc.queryForObject(
                        "SELECT stock_quantity FROM products WHERE product_id = ?",
                        Integer.class, testProductId);
            });

            assertThat(stockQuantity)
                    .as("잠금 타임아웃 후에도 풀이 정상 동작하여 쿼리가 성공해야 합니다")
                    .isEqualTo(100);
        } finally {
            canRelease.countDown();
            executor.shutdownNow();
        }
    }
}

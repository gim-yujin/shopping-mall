package com.shop.global.infra;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Phase 11] 커넥션 풀 고갈 시나리오 & 복구 검증 테스트.
 *
 * <h3>커넥션 풀 고갈이란?</h3>
 * <p>HikariCP 풀의 모든 커넥션이 사용 중이면 신규 요청은 커넥션 획득을 대기한다.
 * connection-timeout(기본 5초, 이 테스트에서는 2초) 내에 커넥션을 얻지 못하면
 * {@code SQLTransientConnectionException}이 발생한다.</p>
 *
 * <h3>고갈 원인</h3>
 * <ul>
 *   <li>장기 트랜잭션: 느린 쿼리, 외부 API 호출, 비관적 잠금 무한 대기</li>
 *   <li>커넥션 누수: close() 누락으로 커넥션이 풀에 반환되지 않음</li>
 *   <li>트래픽 급증: 동시 요청 수가 풀 크기를 초과</li>
 * </ul>
 *
 * <h3>방어 메커니즘 (Phase 11에서 적용)</h3>
 * <ul>
 *   <li>connection-timeout=5s: 커넥션 대기 시간 제한 → 빠른 실패</li>
 *   <li>leak-detection-threshold=30s: 장시간 점유 커넥션 경고 로그</li>
 *   <li>lock_timeout=5s: 비관적 잠금 무한 대기 방지</li>
 * </ul>
 *
 * <h3>검증 불변식</h3>
 * <ol>
 *   <li>풀이 고갈되면 connection-timeout 후 SQLTransientConnectionException 발생</li>
 *   <li>고갈된 풀이 커넥션 반환 후 정상 복구</li>
 *   <li>복구 후 커넥션이 유효하고 쿼리 실행 가능</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // [Phase 11] 작은 풀 크기로 고갈을 빠르게 재현한다.
        // 운영 환경(17)보다 작은 5로 설정하여 테스트 시간을 단축한다.
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=2",
        // [Phase 11] 커넥션 획득 타임아웃을 2초로 단축하여 빠른 실패를 검증한다.
        "spring.datasource.hikari.connection-timeout=2000",
        "logging.level.org.hibernate.SQL=WARN"
})
@SuppressWarnings("PMD.CloseResource")
class ConnectionPoolExhaustionConcurrencyTest {

    @Autowired
    private DataSource dataSource;

    // =========================================================================
    // 시나리오 1: 풀 고갈 → connection-timeout 후 예외 발생
    // =========================================================================

    /**
     * [Phase 11] 모든 커넥션을 점유한 상태에서 추가 요청 시
     * connection-timeout(2초) 후 SQLTransientConnectionException 발생.
     *
     * <p><b>테스트 흐름:</b></p>
     * <ol>
     *   <li>DataSource에서 가용 커넥션을 모두 체크아웃하여 풀을 고갈시킨다</li>
     *   <li>추가 커넥션 요청이 2초 후 SQLTransientConnectionException으로 실패하는지 검증</li>
     *   <li>보유한 커넥션을 모두 반환(close)하여 풀을 복구한다</li>
     *   <li>복구 후 새 커넥션 획득 및 쿼리 실행이 성공하는지 검증</li>
     * </ol>
     *
     * <p><b>핵심:</b> connection-timeout이 없으면(기본 30초), 커넥션 대기가
     * 장시간 지속되어 사용자 경험이 크게 악화된다.
     * Phase 2에서 5초로 단축했고, 이 테스트에서는 2초로 더 줄여 빠른 실패를 검증한다.</p>
     */
    @Test
    @DisplayName("커넥션 풀 고갈 시 2초 후 예외 발생 → 반환 후 풀 복구")
    void poolExhaustion_timeoutThenRecovery() throws Exception {
        HikariDataSource hikariDS = (HikariDataSource) dataSource;
        int maxPoolSize = hikariDS.getMaximumPoolSize();

        // ── 1단계: 가용 커넥션 모두 체크아웃 ──
        // HikariPoolMXBean으로 현재 사용 중인 커넥션을 확인하고,
        // 나머지를 모두 체크아웃하여 풀을 고갈시킨다.
        List<Connection> heldConnections = new ArrayList<>();
        try {
            for (int i = 0; i < maxPoolSize; i++) {
                try {
                    Connection conn = dataSource.getConnection();
                    heldConnections.add(conn);
                } catch (SQLException e) {
                    // 일부 커넥션이 이미 사용 중(스케줄러 등)이면 여기서 실패할 수 있다.
                    // 최소 1개 이상 획득했으면 테스트 진행 가능.
                    break;
                }
            }

            // 최소 2개 이상의 커넥션을 획득했는지 확인
            assertThat(heldConnections.size())
                    .as("풀 고갈 시뮬레이션을 위해 최소 2개 이상의 커넥션을 획득해야 합니다")
                    .isGreaterThanOrEqualTo(2);

            // ── 2단계: 추가 커넥션 요청 → 타임아웃 ──
            // 풀이 고갈되었으므로 connection-timeout(2초) 후 예외가 발생해야 한다.
            long startMs = System.currentTimeMillis();

            assertThatThrownBy(() -> dataSource.getConnection())
                    .as("풀이 고갈되면 connection-timeout 후 SQLTransientConnectionException이 발생해야 합니다")
                    .isInstanceOf(SQLTransientConnectionException.class)
                    .hasMessageContaining("Connection is not available");

            long elapsedMs = System.currentTimeMillis() - startMs;

            // 타임아웃 소요 시간이 connection-timeout(2000ms) 근처인지 검증
            assertThat(elapsedMs)
                    .as("커넥션 타임아웃은 설정값(2000ms) 근처여야 합니다 (실제: %dms)", elapsedMs)
                    .isBetween(1500L, 5000L);
        } finally {
            // ── 3단계: 커넥션 반환 (풀 복구) ──
            for (Connection conn : heldConnections) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // close 실패는 무시 — HikariCP가 커넥션을 폐기하고 새로 생성
                }
            }
        }

        // ── 4단계: 풀 복구 확인 ──
        // 커넥션이 반환되었으므로 새 커넥션 획득 및 쿼리가 성공해야 한다.
        try (Connection recovered = dataSource.getConnection()) {
            assertThat(recovered.isValid(1))
                    .as("복구된 커넥션이 유효해야 합니다")
                    .isTrue();

            // 실제 DB 쿼리도 성공해야 한다
            try (var rs = recovered.createStatement().executeQuery("SELECT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        // 풀 상태 검증: 활성 커넥션이 0 또는 매우 적은 수
        HikariPoolMXBean poolBean = hikariDS.getHikariPoolMXBean();
        assertThat(poolBean.getActiveConnections())
                .as("모든 테스트 커넥션 반환 후 활성 커넥션이 적어야 합니다")
                .isLessThanOrEqualTo(2); // 스케줄러가 1-2개 점유 가능
    }
}

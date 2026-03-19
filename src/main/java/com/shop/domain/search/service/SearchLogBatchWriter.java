package com.shop.domain.search.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * [Phase 19] 검색 로그 JDBC 배치 INSERT 실행기.
 *
 * <h3>기존 문제</h3>
 * <p>JPA {@code SearchLogRepository.save()}는 {@code IDENTITY} 전략 때문에
 * 호출마다 개별 INSERT를 실행한다. Hibernate는 {@code IDENTITY} 전략에서
 * 배치 INSERT를 지원하지 않는다 — 각 INSERT 후 생성된 ID를 즉시 읽어야
 * 영속성 컨텍스트에 등록할 수 있기 때문이다.</p>
 *
 * <p>초당 1000건의 검색이 발생하면 1000개의 개별 INSERT가 실행되어
 * DB 라운드트립 1000회, 트랜잭션 1000회, WAL 쓰기 1000회가 발생한다.</p>
 *
 * <h3>해결: JDBC batchUpdate</h3>
 * <p>JPA를 우회하여 {@code JdbcTemplate.batchUpdate()}를 직접 사용한다.
 * N건의 INSERT를 단일 DB 라운드트립으로 실행하여 처리량을 극대화한다.</p>
 *
 * <p>PostgreSQL JDBC 드라이버의 {@code reWriteBatchedInserts=true} 옵션이 활성화되면
 * 개별 INSERT 대신 multi-value INSERT({@code INSERT INTO ... VALUES (...), (...), (...)})로
 * 자동 변환되어 네트워크 왕복을 추가 절감한다.</p>
 *
 * <h3>트랜잭션 전략</h3>
 * <p>{@code REQUIRES_NEW}를 사용하여 각 배치를 독립 트랜잭션으로 실행한다.
 * 한 배치 실패가 이전에 성공한 배치를 롤백하지 않으며,
 * SearchLogCleanupExecutor와 동일한 패턴이다.</p>
 */
@Component
public class SearchLogBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(SearchLogBatchWriter.class);

    // [Phase 19] ip_address 컬럼이 PostgreSQL inet 타입이므로 ::inet 캐스트 필요.
    // JPA는 columnDefinition="inet"으로 자동 처리하지만, 순수 JDBC에서는 명시적 캐스트가 필수.
    private static final String BATCH_INSERT_SQL =
            "INSERT INTO search_logs (user_id, search_keyword, result_count, ip_address, user_agent, searched_at) " +
                    "VALUES (?, ?, ?, ?::inet, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public SearchLogBatchWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 검색 로그 목록을 단일 트랜잭션 배치 INSERT로 저장한다.
     *
     * <p>{@code JdbcTemplate.batchUpdate()}는 내부적으로
     * {@code PreparedStatement.addBatch()} + {@code executeBatch()}를 사용한다.
     * PostgreSQL JDBC 드라이버가 이를 multi-value INSERT로 재작성하여
     * N건의 INSERT가 단일 네트워크 왕복으로 실행된다.</p>
     *
     * @param entries 저장할 검색 로그 목록 (비어있으면 0 반환)
     * @return 저장된 행 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeBatch(List<SearchLogEntry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }

        jdbcTemplate.batchUpdate(BATCH_INSERT_SQL, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                SearchLogEntry entry = entries.get(i);

                // user_id: nullable (비로그인 사용자)
                if (entry.userId() != null) {
                    ps.setLong(1, entry.userId());
                } else {
                    ps.setNull(1, Types.BIGINT);
                }

                ps.setString(2, entry.keyword());
                ps.setInt(3, entry.resultCount());

                // ip_address: nullable, inet 타입 — SQL에서 ?::inet로 캐스트
                ps.setString(4, entry.ipAddress());

                // user_agent: nullable, TEXT 타입
                ps.setString(5, entry.userAgent());

                // searched_at: 버퍼 추가 시점에 캡처한 검색 발생 시각
                ps.setTimestamp(6, Timestamp.valueOf(entry.searchedAt()));
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });

        if (log.isDebugEnabled()) {
            log.debug("[Phase 19] 검색 로그 배치 저장 완료 — entries={}", entries.size());
        }

        return entries.size();
    }
}

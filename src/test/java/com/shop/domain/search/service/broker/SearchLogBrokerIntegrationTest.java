package com.shop.domain.search.service.broker;

import com.shop.domain.search.service.SearchLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * [Phase 21] Redis Streams 경로 end-to-end 통합 검증.
 *
 * <h3>검증 흐름</h3>
 * <ol>
 *   <li>{@link SearchLogStreamProducer#produce(SearchLogEntry)} 호출</li>
 *   <li>{@link SearchLogBrokerConfig.StreamContainerLifecycle} 가 띄운 {@code StreamMessageListenerContainer}
 *       가 XREADGROUP 으로 메시지를 받아 {@link SearchLogStreamConsumer} 에 전달</li>
 *   <li>Consumer 가 DB INSERT + XACK</li>
 *   <li>Awaitility 로 {@code search_logs} 테이블에 row 가 보일 때까지 대기</li>
 * </ol>
 *
 * <h3>실행 조건</h3>
 * <ul>
 *   <li>Docker 사용 가능 — 없으면 클래스 단위 스킵</li>
 *   <li>CI 환경 비활성화 — {@code RedisStockDeductionBenchmarkTest} 와 동일 사유
 *       (PG max_connections 와 다른 컨텍스트 캐시 경합)</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("redis")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true",
        disabledReason = "통합 테스트는 로컬 전용 — CI 의 PG max_connections 과 컨텍스트 캐시 경합 회피")
@EnabledIf(value = "isDockerAvailable",
        disabledReason = "Docker 데몬 미가용 — Testcontainers redis:7-alpine 기동 불가")
@SuppressWarnings("PMD.CloseResource")
class SearchLogBrokerIntegrationTest {

    static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // 통합 테스트용 짧은 폴링 간격 — 검증을 빨리 끝낸다.
        r.add("app.search-log.broker.poll-block", () -> "100ms");
    }

    @Autowired
    private SearchLogStreamProducer producer;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SearchLogBrokerProperties properties;

    private String testKeyword;

    @BeforeEach
    void setUp() {
        testKeyword = "phase21-it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM search_logs WHERE search_keyword = ?", testKeyword);
        // 스트림은 컨테이너 재시작마다 새로 만들어지지만 안전을 위해 비워둠.
        redisTemplate.delete(properties.stream());
        redisTemplate.delete(properties.dlqStream());
    }

    @Test
    @DisplayName("Producer XADD → Consumer XREADGROUP → DB INSERT → XACK end-to-end")
    void producedEntryReachesDatabase() {
        SearchLogEntry entry = new SearchLogEntry(
                42L, testKeyword, 17, "10.0.0.1", "test-agent",
                LocalDateTime.now().withNano(0));

        producer.produce(entry);

        await().atMost(Duration.ofSeconds(10)).pollInterval(200, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM search_logs WHERE search_keyword = ?",
                    Integer.class, testKeyword);
            assertThat(count).as("Consumer 가 컨슘 후 DB INSERT 완료해야 함").isEqualTo(1);
        });

        // XACK 까지 완료되면 PEL 잔량이 0 이어야 함.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var summary = redisTemplate.opsForStream()
                    .pending(properties.stream(), properties.consumerGroup());
            assertThat(summary).isNotNull();
            assertThat(summary.getTotalPendingMessages())
                    .as("XACK 후 PEL 잔량 0").isZero();
        });
    }
}

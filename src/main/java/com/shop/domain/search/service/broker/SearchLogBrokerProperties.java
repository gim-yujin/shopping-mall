package com.shop.domain.search.service.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * [Phase 21] 검색 로그 Redis Streams 브로커 설정.
 *
 * <p>Phase 19/20 의 인메모리 버퍼 + 파일 WAL 경로를 외부 브로커(Redis Streams) 로 전환할 때
 * 사용하는 설정값. {@code enabled=false} (기본) 일 때는 어떤 브로커 빈도 생성되지 않으며
 * 기존 {@link com.shop.domain.search.service.SearchLogBatchAccumulator} 경로가 그대로 동작한다.</p>
 *
 * <h3>왜 Redis Streams 인가</h3>
 * <p>본 프로젝트는 이미 Redis 인프라(V4 재고 차감) 를 갖고 있다. Redis Streams 는 컨슈머 그룹,
 * PEL(Pending Entries List), XACK/XAUTOCLAIM 으로 at-least-once 와 DLQ 의미론을 네이티브
 * 제공해 추가 인프라 없이 "브로커 기반 최종 일관성" 을 달성할 수 있다. Kafka 대비 운영
 * 복잡도가 낮고, 본 도메인(검색 로그) 의 throughput·내구성 요구(통계 목적, 일부 중복 허용)
 * 에 적합하다.</p>
 *
 * <h3>실패 모드 매핑</h3>
 * <ul>
 *   <li>Consumer DB 쓰기 실패 → XACK 안 함 → 다음 폴링에서 재전달</li>
 *   <li>Consumer 프로세스 크래시 → PEL 잔존 → {@link SearchLogStreamReclaimer} 가
 *       {@code claimIdle} 경과 후 XAUTOCLAIM 으로 회수</li>
 *   <li>{@code maxDeliveryAttempts} 초과 → DLQ 스트림으로 라우팅 후 원본 XACK</li>
 * </ul>
 *
 * @param enabled              브로커 경로 활성화 여부. {@code false} 면 기존 인메모리+WAL 사용.
 * @param stream               메인 스트림 키 (예: {@code search-log-stream})
 * @param consumerGroup        컨슈머 그룹 이름. 기동 시 없으면 자동 생성된다.
 * @param consumerName         이 인스턴스의 컨슈머 이름. 동일 그룹 내 유일해야 한다.
 * @param pollBatchSize        한 번의 XREADGROUP 으로 가져올 최대 메시지 수.
 * @param pollBlock            XREADGROUP block 시간. 메시지 없으면 이 시간 동안 대기.
 * @param dbBatchSize          Consumer 가 DB 로 한 번에 INSERT 할 배치 크기.
 * @param maxStreamLength      XADD 시 {@code MAXLEN ~ N} 로 적용할 근사 트림 상한 (0 이면 미설정).
 * @param claimIdle            PEL 메시지를 회수(XAUTOCLAIM)하기 위한 idle 시간 임계값.
 * @param reclaimInterval      Reclaimer 가 PEL 을 스캔하는 주기.
 * @param maxDeliveryAttempts  이 횟수를 초과해 재전달된 메시지는 DLQ 로 라우팅된다.
 * @param dlqStream            DLQ 스트림 키 (예: {@code search-log-dlq})
 */
@ConfigurationProperties(prefix = "app.search-log.broker")
public record SearchLogBrokerProperties(
        boolean enabled,
        String stream,
        String consumerGroup,
        String consumerName,
        int pollBatchSize,
        Duration pollBlock,
        int dbBatchSize,
        long maxStreamLength,
        Duration claimIdle,
        Duration reclaimInterval,
        int maxDeliveryAttempts,
        String dlqStream
) {

    public SearchLogBrokerProperties {
        if (stream == null || stream.isBlank()) {
            stream = "search-log-stream";
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            consumerGroup = "search-log-cg";
        }
        if (consumerName == null || consumerName.isBlank()) {
            consumerName = "search-log-consumer-" + ProcessHandle.current().pid();
        }
        if (pollBatchSize <= 0) {
            pollBatchSize = 500;
        }
        if (pollBlock == null) {
            pollBlock = Duration.ofSeconds(2);
        }
        if (dbBatchSize <= 0) {
            dbBatchSize = 500;
        }
        if (claimIdle == null) {
            claimIdle = Duration.ofSeconds(60);
        }
        if (reclaimInterval == null) {
            reclaimInterval = Duration.ofSeconds(30);
        }
        if (maxDeliveryAttempts <= 0) {
            maxDeliveryAttempts = 5;
        }
        if (dlqStream == null || dlqStream.isBlank()) {
            dlqStream = stream + "-dlq";
        }
    }
}

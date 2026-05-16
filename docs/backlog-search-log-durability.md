# 분석: Search 로그 내구성 강화 백로그

대상 독자: 운영자

## 배경
검색 로그 경로는 단계적으로 개선되었다.

- **Phase 6**: `@Async("asyncExecutor")` + 개별 DB INSERT.
  - 장점: 구현 단순, 요청 스레드에서 DB INSERT를 분리할 수 있었다.
  - 한계: 큐 포화, 개별 트랜잭션/WAL 비용, 프로세스 비정상 종료 시 유실 가능.
- **현재 (Phase 20)**: `SearchLogBatchAccumulator` 기반 배치 누적 + 선택적 파일 WAL.
  - 장점: 요청 스레드는 버퍼 적재 후 즉시 반환되고, WAL 활성 시 재기동 복구가 가능하다.
  - 한계: 브로커/DB 장애까지 포함한 최종 일관성은 아직 보장하지 않는다.

이 문서는 완료된 Phase 19/20의 배경과, 아직 남아 있는 브로커 기반 전환 과제를 함께 기록한다.

## Phase 19 (완료): 인메모리 배치 쓰기 최적화

### 구현 내용
개별 `@Async` INSERT를 제거하고, 인메모리 배치 누적기로 전환하여 DB 라운드트립을 대폭 감소시켰다.

- **SearchLogEntry**: JPA 엔티티 대신 불변 record 값 객체. 영속성 컨텍스트 비용 없음.
- **SearchLogBatchAccumulator**: `ConcurrentLinkedQueue` 기반 lock-free 버퍼. 주기적(5초)으로 플러시하며, 한 번의 플러시에서 최대 500건씩 배치 처리.
- **SearchLogBatchWriter**: `JdbcTemplate.batchUpdate()`로 JDBC 배치 INSERT. JPA `IDENTITY` 전략의 배치 불가 제약을 우회.
- **SearchLogBatchMeterBinder**: Micrometer 메트릭 (버퍼 크기, 플러시 횟수, 폐기 건수) Prometheus 노출.

### 성능 개선
| 지표 | 기존 | 개선 후 |
|------|------|---------|
| 초당 1000건 DB 라운드트립 | 1000회 | 2회 (5초 간격) |
| asyncExecutor 큐 점유 | 검색당 1슬롯 | 0슬롯 |
| 트랜잭션 수 | 1000회/초 | 2회/초 |
| DB WAL 쓰기 | 1000회/초 | 2회/초 |

### Graceful Shutdown
`DisposableBean.destroy()`에서 잔여 버퍼를 플러시하여 배포/재시작 시 유실 최소화.

### 설정
```yaml
app:
  search-log:
    batch:
      flush-interval-ms: 5000    # 플러시 주기 (밀리초)
      batch-size: 500            # 플러시당 최대 건수
      max-buffer-size: 10000     # 버퍼 최대 크기 (초과 시 폐기)
```

## Phase 20 (완료): 파일 기반 WAL 내구성

### 문제
Phase 19의 인메모리 배치 누적기는 Graceful Shutdown(SIGTERM)에서는 잔여 버퍼를 플러시하지만,
프로세스 비정상 종료(kill -9, OOM Killer, 하드웨어 장애) 시에는 인메모리 버퍼의 데이터가
복구 불가능하게 유실된다. 최대 5초(플러시 주기) × 초당 검색 건수만큼의 로그가 유실될 수 있다.

### 해결: 세그먼트 기반 Write-Ahead Log (WAL)
외부 인프라(Kafka, RabbitMQ) 없이 파일 시스템만으로 내구성을 확보한다.

### 구현 내용
- **SearchLogWalManager**: WAL 세그먼트 파일의 append/rotate/recover/delete를 관리.
  JSON Lines 형식으로 한 줄에 하나의 SearchLogEntry를 직렬화한다.
- **SearchLogWalConfig**: `app.search-log.wal.enabled=true`일 때만 WAL 빈을 생성하는
  `@ConditionalOnProperty` 설정. 기존 환경에 영향 없이 점진 전환 가능.
- **SearchLogWalRecovery**: `ApplicationRunner`로 기동 시 잔존 세그먼트를 읽어
  DB에 직접 배치 INSERT. 모든 배치 처리 완료 후 세그먼트를 삭제한다.
- **SearchLogBatchAccumulator 통합**: `add()` 시 WAL append → 인메모리 버퍼 추가.
  `flush()` 시 세그먼트 rotate → 배치 처리(저장 또는 폐기) → 세그먼트 삭제.
- **SearchLogBatchMeterBinder 확장**: WAL 관련 메트릭 2종 추가
  (`shop.search.log.wal.bytes.written`, `shop.search.log.wal.recovered.count`).

### 세그먼트 생명주기
```
1. 신규 세그먼트 생성 → 현재 세그먼트로 지정
2. append() 호출마다 현재 세그먼트에 JSON Lines append
3. flush() 시점에 rotateSegment(): 현재 세그먼트를 닫고 새 세그먼트를 연다
4. 배치 처리 완료 후 deleteSegment(): 닫힌 세그먼트를 삭제
5. 기동 시 recoverAll(): 잔존 세그먼트(3~4 사이 크래시)를 읽어 DB로 복구
```

### 중복 가능성
크래시 타이밍에 따라 이미 DB에 저장된 일부 엔트리가 WAL에도 남아 있을 수 있다.
(배치 처리 완료 → 세그먼트 삭제 전 크래시) 복구 시 소수의 중복 INSERT가 발생할 수 있지만,
검색 로그는 인기 검색어 통계 목적이므로 통계 정확도에 무시할 수준이다.

### 설정
```yaml
app:
  search-log:
    wal:
      enabled: true                  # WAL 활성화 여부
      dir: ./data/wal/search-log     # WAL 세그먼트 디렉터리
      sync-on-append: false          # true: 내구성 우선, false: 처리량 우선
```

### 운영 가이드
- WAL 디렉터리는 애플리케이션 프로세스의 쓰기 권한이 필요하다.
- Docker 환경에서는 영구 볼륨에 마운트하여 컨테이너 재시작 시 세그먼트 보존.
- `sync-on-append=false`(기본값)는 OS 페이지 캐시에 의존. 대부분의 프로세스 크래시에서 복구 가능.
  완전한 fsync는 검색 로그 대비 비용이 과도하여 적용하지 않음.
- 기동 시 `WAL 복구 완료` 로그가 출력되면 이전 프로세스의 비정상 종료가 있었음을 의미.

## Phase 21 (완료): Redis Streams 브로커 전환

### 결정 — 왜 Redis Streams 인가
Kafka/RabbitMQ 대신 **Redis Streams** 를 선택했다. 본 프로젝트는 V4 재고 차감(`@ActiveProfiles("redis")`)
으로 Redis 인프라를 이미 갖고 있고, Redis Streams 는 컨슈머 그룹·PEL·XACK/XAUTOCLAIM 으로
at-least-once 와 DLQ 의미론을 네이티브 제공하기 때문에 **추가 인프라 0** 으로 "브로커 기반
최종 일관성" 을 달성할 수 있다. Kafka 대비 운영 복잡도가 낮고, 본 도메인(검색 로그) 의 throughput·
내구성 요구(통계 목적, 일부 중복 허용) 에 적합하다.

### 활성화
```yaml
spring:
  profiles:
    active: redis
app:
  search-log:
    broker:
      enabled: true   # Redis 프로파일에서 기본 true (application-redis.yml)
```

`enabled=false` 또는 redis 프로파일 미활성 시 어떤 브로커 빈도 생성되지 않으며 기존
Phase 19/20 경로(인메모리 + 파일 WAL) 가 그대로 동작한다.

### 구현 컴포넌트
- **`SearchLogBrokerProperties`** — `app.search-log.broker.*` 설정 record.
- **`SearchLogStreamProducer`** — XADD 래퍼. 명시 필드 직렬화(JSON-in-field 가 아님 — Redis CLI
  로 즉시 디버깅 가능). nullable 필드는 빈 문자열로 직렬화 후 컨슈머에서 null 복원.
- **`SearchLogStreamConsumer`** — `StreamMessageListenerContainer` 가 폴링한 메시지를 받아
  `SearchLogBatchWriter` 로 INSERT 후 XACK. 실패 시 XACK 하지 않아 다음 폴링에서 재전달.
- **`SearchLogStreamReclaimer`** — `@Scheduled` 로 `XPENDING` 스캔. idle 임계 초과 메시지를
  `XCLAIM` 으로 현재 컨슈머로 이전해 재처리. `maxDeliveryAttempts` 초과 시 `${dlqStream}`
  으로 라우팅 후 원본 XACK.
- **`SearchLogBrokerConfig`** — `@Profile("redis")` + `@ConditionalOnProperty` 로 위 빈들과
  컨슈머 그룹 생성·컨테이너 lifecycle 을 묶는다.
- **`SearchLogBrokerMeterBinder`** — Producer/Consumer/Reclaimer 카운터(produced/consumed/
  reclaimed/dlq.routed) + Redis 쿼리 기반 게이지(stream.length/dlq.length/pel.size) 를
  Micrometer 로 노출. 브로커 활성 시에만 빈 등록.

### 폴백 — Producer 측 Redis 장애
`SearchService.logSearch` 는 `streamProducer.produce()` 가 예외를 던지면 기존 인메모리 경로
(`SearchLogBatchAccumulator`) 로 폴백한다. 검색 응답을 절대 막지 않는다는 정책이다.

### 실패 모드 매핑
| 시나리오 | 결과 |
|---|---|
| Producer Redis 단절 | 인메모리+WAL 경로로 자동 폴백 |
| Consumer DB INSERT 실패 | XACK 미실행 → 다음 폴링에서 재전달 |
| Consumer 프로세스 크래시 (XACK 전) | PEL 잔존 → Reclaimer 가 idle 회수 |
| Consumer 무한 실패 (poison message) | `maxDeliveryAttempts` 초과 시 DLQ 스트림 라우팅 |

### 중복
At-least-once 의미론상 일부 중복이 발생할 수 있다(원래 컨슈머가 INSERT 후 XACK 전 죽은 경우).
검색 로그는 통계 목적이고 [기존 정책](#중복-가능성) 과 동일하게 소수 중복은 허용된다.

### 검증
- 단위: `SearchLogStreamProducerTest`, `SearchLogStreamConsumerTest`
- 통합: `SearchLogBrokerIntegrationTest` (testcontainers redis:7-alpine, Docker 가드)

### 운영
- **DLQ 모니터링**: `XLEN search-log-dlq` 가 0 이상이면 컨슘 실패 누적. 메시지 내용은
  `XRANGE search-log-dlq - +` 로 즉시 조회 가능.
- **PEL 상태**: `XPENDING search-log-stream search-log-cg` 로 적체 확인.
- **스트림 트림**: 본 phase 는 자동 트림을 적용하지 않는다. 운영 환경에서 디스크 증가가 우려되면
  별도 cron 으로 `XTRIM MAXLEN ~ N` 적용 권장.

## Phase 22-1 (완료): 배치 INSERT 복원

### 문제
Phase 21 의 `StreamListener.onMessage` 단건 콜백은 메시지마다 INSERT 1회 + XACK 1회를 호출해
Phase 19 의 JDBC 배치 INSERT 이점을 잃었다. 초당 1,000 건 처리 시 DB 라운드트립 1,000 회 +
XACK 1,000 회로 회귀.

### 해결: 버퍼 누적 + 주기/임계 flush
`SearchLogStreamConsumer` 가 `StreamListener` 콜백을 받으면 인메모리 버퍼에 누적만 하고,
다음 두 조건 중 하나에서 flush 한다.

1. 버퍼가 `dbBatchSize` (기본 500) 도달 — `onMessage` 내부에서 즉시 flush
2. `batchFlushInterval` (기본 1초) 주기 스케줄러 — `@Scheduled` 호출

flush 는 단일 `writer.writeBatch(entries)` + 배치 XACK(`acknowledge` varargs) 으로 N 건을
한 번에 처리한다. `DisposableBean.destroy()` 가 graceful shutdown 시 잔여 버퍼를 flush.

### 동시성
`onMessage` 는 `StreamMessageListenerContainer` 의 내부 스레드에서 직렬 호출되고, `scheduledFlush`
와 `destroy` 는 별도 스레드에서 호출된다. 모든 진입점이 `bufferLock` 으로 drain 까지 보호하고
무거운 DB 작업은 락 밖에서 실행한다. 동시 flush 호출이 가능하지만 `writer.writeBatch` 의
`REQUIRES_NEW` 가 독립 트랜잭션을 보장하므로 충돌 없다.

### at-least-once 보장 (변경 없음)
- flush 성공(INSERT + XACK) → PEL 제거
- INSERT 실패 → XACK 미실행 → Reclaimer 가 idle 회수
- 버퍼에만 있는 상태에서 크래시 → PEL 그대로 → Reclaimer 회수
- INSERT 성공 후 XACK 전 크래시 → 중복 INSERT (통계 목적이라 허용)

### 메트릭
`shop.search.log.broker.flush.batches` 추가. 평균 배치 크기는 `consumed.total / flush.batches`
로 산출.

## 향후 (선택적 Phase 22+)
- 자동 스트림 트림 — Producer 측 MAXLEN 또는 별도 트림 스케줄러
- 컨슈머 수평 확장 시나리오 측정 — 동일 그룹의 다중 컨슈머 처리량 벤치

## 작업 항목
- [x] 인메모리 배치 누적기 + JDBC 배치 INSERT (Phase 19)
- [x] Micrometer 메트릭 + Prometheus 노출 (Phase 19)
- [x] Graceful Shutdown 플러시 (Phase 19)
- [x] 파일 기반 WAL 세그먼트 관리자 (Phase 20)
- [x] WAL 기동 시 복구 (ApplicationRunner) (Phase 20)
- [x] SearchLogBatchAccumulator WAL 통합 (Phase 20)
- [x] WAL 메트릭 Prometheus 노출 (Phase 20)
- [x] 이벤트 스키마 정의 — `SearchLogStreamProducer` 의 명시 필드 매핑 (Phase 21)
- [x] 이벤트 발행 컴포넌트 — `SearchLogStreamProducer` (Phase 21)
- [x] 브로커 토픽/큐 — Redis Streams 키 `search-log-stream` (Phase 21)
- [x] 소비자 서비스 및 재시도 — `SearchLogStreamConsumer` + `SearchLogStreamReclaimer` (Phase 21)
- [x] DLQ/재처리 — `XCLAIM` + `${stream}-dlq` 라우팅 (Phase 21)
- [x] 점진 전환 — `@ConditionalOnProperty` opt-in + Producer 측 자동 폴백 (Phase 21)
- [~] end-to-end 전달 성공률/지연/적체 모니터링 — 메트릭 노출 완료(`SearchLogBrokerMeterBinder`, Phase 21 후속). Prometheus/Grafana 대시보드 구성은 운영 단계 과제.

## 수용 기준 (Acceptance Criteria)
- [x] 기존 검색 응답 p95/p99 지연 악화 없음 (Phase 19: HTTP 스레드 블로킹 제거)
- [x] 운영자가 적체/유실/재처리 상태를 메트릭으로 즉시 확인 가능 (Phase 19: Micrometer)
- [x] 재배포/프로세스 재시작 구간에서도 로그 유실률 SLO 충족 (Phase 20: 파일 기반 WAL)
- [x] 브로커/DB 장애 시에도 재처리로 최종 일관성 보장 (Phase 21: Redis Streams + Reclaimer + DLQ)

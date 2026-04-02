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
- **SearchLogBatchAccumulator**: `ConcurrentLinkedQueue` 기반 lock-free 버퍼. 주기적(5초) 또는 임계치(500건) 도달 시 플러시.
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
  DB에 직접 배치 INSERT. 복구 완료 후 세그먼트를 삭제한다.
- **SearchLogBatchAccumulator 통합**: `add()` 시 WAL append → 인메모리 버퍼 추가.
  `flush()` 시 세그먼트 rotate → DB flush → 세그먼트 삭제.
- **SearchLogBatchMeterBinder 확장**: WAL 관련 메트릭 2종 추가
  (`shop.search.log.wal.bytes.written`, `shop.search.log.wal.recovered.count`).

### 세그먼트 생명주기
```
1. 신규 세그먼트 생성 → 현재 세그먼트로 지정
2. add() 호출마다 현재 세그먼트에 JSON Lines append
3. flush() 시점에 rotateSegment(): 현재 세그먼트를 닫고 새 세그먼트를 연다
4. DB flush 성공 후 deleteSegment(): 닫힌 세그먼트를 삭제
5. 기동 시 recoverAll(): 잔존 세그먼트(3~4 사이 크래시)를 읽어 DB로 복구
```

### 중복 가능성
크래시 타이밍에 따라 이미 DB에 저장된 엔트리가 WAL에도 남아 있을 수 있다.
(DB flush 성공 → 세그먼트 삭제 전 크래시) 복구 시 소수의 중복 INSERT가 발생하지만,
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

## 목표 (미구현 — 향후 Phase)
`SearchService.logSearch`를 동기 비즈니스 경로에서 분리해, 메시지 큐/이벤트 로그 기반의 **내구성 있는 비동기 파이프라인**으로 전환한다.

## 제안 아키텍처 (향후)
1. 검색 요청 시 애플리케이션은 `SearchLogged` 이벤트를 발행
2. 이벤트를 Kafka/RabbitMQ(또는 managed event log)에 적재
3. 별도 consumer가 배치/스트리밍으로 `search_log` 저장
4. DLQ 및 재처리 전략으로 실패 이벤트 복구

## 작업 항목
- [x] 인메모리 배치 누적기 + JDBC 배치 INSERT (Phase 19)
- [x] Micrometer 메트릭 + Prometheus 노출 (Phase 19)
- [x] Graceful Shutdown 플러시 (Phase 19)
- [x] 파일 기반 WAL 세그먼트 관리자 (Phase 20)
- [x] WAL 기동 시 복구 (ApplicationRunner) (Phase 20)
- [x] SearchLogBatchAccumulator WAL 통합 (Phase 20)
- [x] WAL 메트릭 Prometheus 노출 (Phase 20)
- [ ] 이벤트 스키마 정의 (`searchId`, `userId`, `keyword`, `resultCount`, `ipAddress`, `userAgent`, `occurredAt`)
- [ ] 이벤트 발행 컴포넌트 추가 (idempotency key 포함)
- [ ] 브로커 토픽/큐, 보존 정책, 파티션 전략 설계
- [ ] 소비자(consumer) 서비스 및 재시도/백오프 정책 구현
- [ ] DLQ/재처리 운영 runbook 작성
- [ ] end-to-end 전달 성공률/지연/적체 모니터링 대시보드 구성
- [ ] 점진 전환(dual-write 또는 shadow traffic) 계획 수립

## 수용 기준 (Acceptance Criteria)
- [x] 기존 검색 응답 p95/p99 지연 악화 없음 (Phase 19: HTTP 스레드 블로킹 제거)
- [x] 운영자가 적체/유실/재처리 상태를 메트릭으로 즉시 확인 가능 (Phase 19: Micrometer)
- [x] 재배포/프로세스 재시작 구간에서도 로그 유실률 SLO 충족 (Phase 20: 파일 기반 WAL)
- [ ] 브로커/DB 장애 시에도 재처리로 최종 일관성 보장

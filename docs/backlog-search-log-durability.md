# 분석: Search 로그 내구성 강화 백로그

대상 독자: 운영자

## 배경
현재 검색 로그는 애플리케이션 프로세스 내 `@Async` 실행 + DB 저장 방식이다.
- 장점: 구현 단순, 응답 지연 감소
- 한계: 프로세스 비정상 종료/재배포 타이밍/큐 포화 시 로그 유실 가능

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
| WAL 쓰기 | 1000회/초 | 2회/초 |

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
- [ ] 재배포/프로세스 재시작 구간에서도 로그 유실률 SLO 충족
- [ ] 브로커/DB 장애 시에도 재처리로 최종 일관성 보장

# 운영가이드: 검색 로그 운영 정책

대상 독자: 운영자

## 1) 기본 정책 (현재)
- `SearchService.logSearch`는 요청 스레드에서 `SearchLogEntry`를 만들고
  `SearchLogBatchAccumulator.add()`로 버퍼에 즉시 적재한다.
- 적재된 로그는 `SearchLogBatchWriter`가 JDBC 배치 INSERT로 저장한다.
  - `app.search-log.batch.flush-interval-ms=5000` (기본)
  - `app.search-log.batch.batch-size=500` (기본)
  - `app.search-log.batch.max-buffer-size=10000` (기본)
- 저장 실패나 버퍼 오버플로우가 발생해도 비즈니스 요청(검색 결과 응답)은 계속 진행한다.
  - 검색 로그는 분석/통계 목적 데이터로 간주한다.
  - 유실 여부는 메트릭으로 추적한다.

## 2) 내구성 모드
- 기본값(`app.search-log.wal.enabled=false`): 인메모리 배치 누적 기반 **best-effort**.
  - 프로세스 비정상 종료 시 flush 전 버퍼 데이터는 유실될 수 있다.
- 강화 모드(`app.search-log.wal.enabled=true`): 버퍼 적재 전에 WAL 세그먼트에 append하고,
  기동 시 `SearchLogWalRecovery`가 잔존 세그먼트를 복구한다.
  - 기본 WAL 경로: `./data/wal/search-log`
  - `app.search-log.wal.sync-on-append=false`는 처리량 우선 설정이다.
  - DB flush 성공 후 세그먼트 삭제 직전에 크래시하면 소수의 중복 복구가 가능하다.

## 3) 배포/재시작 시 종료 정책
- `SearchLogBatchAccumulator.destroy()`가 잔여 버퍼를 flush하고 WAL writer를 닫는다.
- WAL 활성 시 rotate 이후 남은 세그먼트는 다음 기동에서 자동 복구된다.
- Docker 환경에서는 WAL 디렉터리를 영구 볼륨에 마운트한다.

## 4) 관측(Observability) 지표
아래 메트릭을 상시 모니터링한다.
- `shop.search.log.buffer.size`: 현재 버퍼 대기 건수
- `shop.search.log.buffer.max`: 버퍼 최대 크기
- `shop.search.log.buffer.fill.ratio`: 버퍼 사용률
- `shop.search.log.added.total`: 누적 적재 건수
- `shop.search.log.flushed.total`: 누적 DB 저장 건수
- `shop.search.log.dropped.total`: 누적 폐기 건수 (오버플로우 + 저장 실패)
- `shop.search.log.flush.count`: 누적 플러시 횟수
- `shop.search.log.wal.bytes.written`: WAL 누적 기록 바이트 (WAL 활성 시)
- `shop.search.log.wal.recovered.count`: 기동 시 WAL 복구 건수 (WAL 활성 시)

권장 운영 체크포인트:
1. `shop.search.log.buffer.fill.ratio`가 0.8을 지속 초과하는지 확인
2. `increase(shop.search.log.dropped.total[5m]) > 0` 발생 시 저장 실패/오버플로우 원인 조사
3. WAL 활성 환경에서 `shop.search.log.wal.recovered.count > 0`이면 이전 비정상 종료 원인 확인

## 5) 향후 전환 판단 기준
아래 항목 중 하나라도 해당되면 현재 배치 누적 + 파일 WAL 방식에서
브로커 기반 **내구성 있는 비동기 파이프라인**으로 전환한다.
- 검색 로그가 정산/컴플라이언스/감사 근거 데이터인 경우
- 검색 분석 데이터 유실이 상품/광고/추천 핵심 KPI를 크게 왜곡하는 경우
- 브로커/DB 장애 시에도 재처리로 최종 일관성이 필요한 경우

이 경우에는 백로그 문서(`docs/backlog-search-log-durability.md`)의 Phase 20 이후 작업을
우선순위에 따라 수행한다.

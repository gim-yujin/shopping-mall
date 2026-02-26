# Backlog: SearchService.logSearch 내구성 강화 (큐/이벤트 로그 분리)

## 배경
현재 검색 로그는 애플리케이션 프로세스 내 `@Async` 실행 + DB 저장 방식이다.
- 장점: 구현 단순, 응답 지연 감소
- 한계: 프로세스 비정상 종료/재배포 타이밍/큐 포화 시 로그 유실 가능

## 목표
`SearchService.logSearch`를 동기 비즈니스 경로에서 분리해, 메시지 큐/이벤트 로그 기반의 **내구성 있는 비동기 파이프라인**으로 전환한다.

## 제안 아키텍처
1. 검색 요청 시 애플리케이션은 `SearchLogged` 이벤트를 발행
2. 이벤트를 Kafka/RabbitMQ(또는 managed event log)에 적재
3. 별도 consumer가 배치/스트리밍으로 `search_log` 저장
4. DLQ 및 재처리 전략으로 실패 이벤트 복구

## 작업 항목
- [ ] 이벤트 스키마 정의 (`searchId`, `userId`, `keyword`, `resultCount`, `ipAddress`, `userAgent`, `occurredAt`)
- [ ] 이벤트 발행 컴포넌트 추가 (idempotency key 포함)
- [ ] 브로커 토픽/큐, 보존 정책, 파티션 전략 설계
- [ ] 소비자(consumer) 서비스 및 재시도/백오프 정책 구현
- [ ] DLQ/재처리 운영 runbook 작성
- [ ] end-to-end 전달 성공률/지연/적체 모니터링 대시보드 구성
- [ ] 점진 전환(dual-write 또는 shadow traffic) 계획 수립

## 수용 기준 (Acceptance Criteria)
- [ ] 재배포/프로세스 재시작 구간에서도 로그 유실률 SLO 충족
- [ ] 브로커/DB 장애 시에도 재처리로 최종 일관성 보장
- [ ] 기존 검색 응답 p95/p99 지연 악화 없음
- [ ] 운영자가 적체/유실/재처리 상태를 메트릭으로 즉시 확인 가능

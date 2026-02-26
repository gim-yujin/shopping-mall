# 검색 로그 운영 정책

## 1) 기본 정책 (현재)
- `SearchService.logSearch`는 요청 처리 지연을 줄이기 위해 `@Async("asyncExecutor")`로 비동기 저장한다.
- 현재 운영 정책은 **best-effort(유실 허용)** 이다.
  - 저장 실패 시 비즈니스 요청(검색 결과 응답)은 계속 진행한다.
  - 검색 로그는 분석/통계 목적 데이터로 간주한다.

## 2) 배포/재시작 시 종료 정책
- `ThreadPoolTaskExecutor` 종료 시 다음 정책을 적용한다.
  - `setWaitForTasksToCompleteOnShutdown(true)`
  - `setAwaitTerminationSeconds(${app.async.await-termination-seconds})`
- 목적: 재배포 구간에서 in-flight 로그를 최대한 drain 하여 유실률을 낮춘다.

## 3) 관측(Observability) 지표
재배포 전후 유실률 추정을 위해 아래 메트릭을 상시 모니터링한다.
- `app.async.executor.queue.size`: 현재 대기 큐 길이
- `app.async.executor.rejected.total`: 큐 포화 등으로 인한 작업 거절 누적 수
- `app.async.executor.completed.total`: 비동기 작업 완료 누적 수

권장 운영 체크포인트:
1. 배포 직전/직후 `rejected.total` 급증 여부 확인
2. 배포 직후 `queue.size`가 빠르게 0으로 수렴하는지 확인
3. 트래픽 대비 `completed.total` 증가량 추세가 유지되는지 확인

## 4) 내구성 요구사항 판단 기준
아래 항목 중 하나라도 해당되면 best-effort가 아닌 **내구성 강화(유실 최소화/방지)** 정책으로 전환한다.
- 검색 로그가 정산/컴플라이언스/감사 근거 데이터인 경우
- 검색 분석 데이터 유실이 상품/광고/추천 핵심 KPI를 크게 왜곡하는 경우
- 장애 시점 유실 허용 임계치(SLO) 합의가 불가능한 경우

이 경우에는 백로그 문서(`docs/backlog-search-log-durability.md`)의 큐/이벤트 로그 분리 작업을 우선순위에 따라 수행한다.

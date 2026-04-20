# docs/ 문서 인덱스

`docs/` 하위 문서를 카테고리별로 관리하는 인덱스다.  
신규 문서를 추가하거나 문서 상태가 변경되면 **반드시 이 파일을 함께 갱신**한다.

## 태그 기준
- **목적(Purpose)**: 문서가 해결하려는 문제/활용 시나리오
- **최신성(Last updated)**: 최신 커밋 기준 갱신일 (`YYYY-MM-DD`)
- **기준 여부(Status)**:
  - `SSOT`: 해당 주제의 최신 기준 문서
  - `Reference`: 참고/배경 문서(현행 기준 보조)
  - `Archive`: 보관용 문서(현행 기준 아님)

---

## Guide

| 문서 | Purpose | Last updated | Status |
|---|---|---|---|
| [`docs/query-optimization.md`](./query-optimization.md) | N+1 해결 전략 종합 정리: JOIN FETCH, 2-쿼리 패턴, CQRS 플랫 프로젝션, @BatchSize 등 | 2026-03-24 | SSOT |
| [`docs/implementation-details.md`](./implementation-details.md) | 아키텍처, 주문 처리, 데이터 모델의 구현 세부를 정리 | 2026-03-14 | Reference |
| [`docs/order-invariant-checks.md`](./order-invariant-checks.md) | 주문 도메인 무결성 점검 규칙과 운영 확인 절차 제공 | 2026-03-14 | SSOT |
| [`docs/search-log-ops-policy.md`](./search-log-ops-policy.md) | 검색 로그 저장/정리 정책과 운영 기준 정의 | 2026-04-10 | SSOT |
| [`docs/rest-api-guide.md`](./rest-api-guide.md) | REST API 엔드포인트 사용 설명서 (요청/응답 형식, 인증, 멱등성 키) | 2026-04-06 | SSOT |
| [`docs/guide-doc-naming-conventions.md`](./guide-doc-naming-conventions.md) | `docs/` 문서 네이밍/분류 컨벤션 관리 | 2026-03-14 | SSOT |
| [`docs/guide-loadtest-env-setup.md`](./guide-loadtest-env-setup.md) | 부하 테스트 전용 DB `shopping_mall_loadtest_db` 구축 + 500K 시드 생성기 사용 절차 (Phase 21 재측정 준비, 생성기 이후 스키마 재적용 포함) | 2026-04-21 | SSOT |
| [`docs/문서_수치_갱신_규칙.md`](./문서_수치_갱신_규칙.md) | README 수치 검증 및 갱신 절차 가이드 | 2026-03-14 | SSOT |
| [`docs/pmd-method-naming-conventions-exceptions.md`](./pmd-method-naming-conventions-exceptions.md) | PMD 메서드 네이밍 예외 정책과 적용 기준 정리 | 2026-03-14 | Reference |

## ADR

| 문서 | Purpose | Last updated | Status |
|---|---|---|---|
| [`docs/adr/ADR-0001-tier-criteria-cumulative-total-spent.md`](./adr/ADR-0001-tier-criteria-cumulative-total-spent.md) | 회원 등급 산정 기준을 누적 `total_spent`로 결정한 배경 기록 | 2026-03-14 | SSOT |
| [`docs/adr/ADR-0002-point-accrual-on-delivery-and-cancel-policy.md`](./adr/ADR-0002-point-accrual-on-delivery-and-cancel-policy.md) | 포인트 적립 시점과 취소/반품 정산 정책 결정 기록 | 2026-03-14 | SSOT |
| [`docs/adr/template.md`](./adr/template.md) | ADR 문서 신규 작성 시 사용하는 표준 템플릿 | 2026-03-14 | Reference |

## Benchmark

| 문서 | Purpose | Last updated | Status |
|---|---|---|---|
| [`docs/load-test-benchmark.md`](./load-test-benchmark.md) | k6 부하 테스트 최적화 Before/After 비교 (5가지 조건, 4개 시나리오) + Phase 21 500K 재측정 | 2026-04-21 | SSOT |

## Analysis

| 문서 | Purpose | Last updated | Status |
|---|---|---|---|
| [`docs/facade-overhead.md`](./facade-overhead.md) | 파사드 계층 도입에 따른 비용/효과 분석 | 2026-03-14 | Reference |
| [`docs/tier-policy-decision-log-2026-03-14.md`](./tier-policy-decision-log-2026-03-14.md) | 등급 정책 결정 과정의 논의 로그 보관 | 2026-03-14 | Reference |
| [`docs/backlog-search-log-durability.md`](./backlog-search-log-durability.md) | 검색 로그 내구성 관련 백로그/검토 항목 분석 | 2026-04-10 | Reference |
| [`docs/analysis-execution-plan-optimization.md`](./analysis-execution-plan-optimization.md) | Execution Plan 관점 최적화 검토: 6건 개선 항목 식별 및 우선순위 매트릭스 | 2026-04-03 | SSOT |
| [`docs/analysis-map-cache-concurrency-review-2026-04-03.md`](./analysis-map-cache-concurrency-review-2026-04-03.md) | Map/Cache 사용처와 동시성 안전성 점검 (`HashMap`, `LinkedHashMap`, `ConcurrentHashMap`, `ConcurrentMapCache`, `Hashtable`) | 2026-04-03 | SSOT |
| [`docs/analysis-product-list-count-cache-split.md`](./analysis-product-list-count-cache-split.md) | 상품 목록 p95 병목 — COUNT 쿼리 공유 캐시 분리(Phase 21) 배경/측정/한계 | 2026-04-21 | SSOT |

## Archive

| 문서 | Purpose | Last updated | Status |
|---|---|---|---|
| [`docs/archive/shopmall-phase0-report.md`](./archive/shopmall-phase0-report.md) | 초기 단계 산출물/회고 리포트 보관 | 2026-03-14 | Archive |

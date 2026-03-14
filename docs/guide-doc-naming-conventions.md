# 운영가이드: 문서 네이밍 규칙

대상 독자: 신규 개발자

## 목적
`docs/` 하위 문서의 파일명만 보고도 문서 목적(운영가이드/결정기록/회고/아카이브/분석)을 즉시 식별할 수 있도록 네이밍 규칙을 정의한다.

## 기본 규칙
- 파일명은 소문자 kebab-case를 사용한다.
- 접두사 뒤에는 주제를 붙인다. (예: `guide-search-log-ops.md`)
- 날짜가 필요한 문서는 파일명 끝에 `YYYY-MM-DD`를 추가한다.

## 접두사 규칙
- `guide-`: 운영 절차, 점검 체크리스트, 실행 방법 등 운영가이드 문서
- `adr-`: Architecture Decision Record(의사결정 기록) 문서
- `retrospective-`: 프로젝트/스프린트 회고 문서
- `archive-`: 더 이상 최신 기준이 아닌 보관용 문서
- `analysis-`: 성능, 구조, 장애 원인 등 분석 문서

## 예시
- `guide-order-invariant-checks.md`
- `adr-tier-policy-2026-03-14.md`
- `retrospective-phase0.md`
- `archive-shopmall-phase0-report.md`
- `analysis-thymeleaf-rendering-cost.md`

## 적용 원칙
- 새 문서 작성 시 반드시 위 접두사를 사용한다.
- 기존 문서는 즉시 파일명 변경이 어렵다면, 최소한 제목(H1)에 문서 목적을 먼저 반영한다.
- 보관 문서는 `docs/archive/` 하위에 위치시키는 것을 권장한다.

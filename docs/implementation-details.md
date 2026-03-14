# 구현 상세 문서

이 문서는 `README.md`의 요약 항목을 보완하는 구현 상세 설명을 제공합니다.

## 문서 맵

- 아키텍처/구성 개요: `README.md`의 "프로젝트 개요", "패키지 구조", "운영/설계 포인트"
- 주문 무결성 규칙: [docs/order-invariant-checks.md](./order-invariant-checks.md)
- 검색 로그 운영 정책: [docs/search-log-ops-policy.md](./search-log-ops-policy.md)
- 검색 로그 내구성 백로그: [docs/backlog-search-log-durability.md](./backlog-search-log-durability.md)
- 티어 정책 결정 기록: [docs/tier-policy-decision-log-2026-03-14.md](./tier-policy-decision-log-2026-03-14.md)
- 파사드 구조 관련 배경: [docs/facade-overhead.md](./facade-overhead.md)

## 운영 원칙

1. 최신 기준(SSOT)은 `README.md`에 둡니다.
2. 상세 구현/배경/의사결정은 `docs/`의 개별 문서에서 관리합니다.
3. 기준 변경 시 `README.md`와 관련 `docs/`를 함께 동기화합니다.

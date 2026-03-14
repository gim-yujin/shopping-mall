# 🛒 Shopping Mall

대규모 트래픽과 동시성 이슈를 고려해 설계한 **Spring Boot 기반 이커머스 웹 애플리케이션**입니다.  
서버 사이드 렌더링(Thymeleaf)과 일부 REST API를 함께 사용하며, 주문/재고/쿠폰/포인트/반품 등 실제 쇼핑몰 핵심 시나리오를 중심으로 구현되어 있습니다.

> 📌 **최신 기준 문서(SSOT, Single Source of Truth)는 이 `README.md`입니다.**
> 구현/운영 세부 내용은 아래 연계 문서(`docs/`)를 참고하고, 기준 정책 변경 시 README와 연계 문서를 함께 갱신합니다.

---

## 1) 프로젝트 개요

- **아키텍처**: 단일 모놀리식 애플리케이션 (`global` + `domain` 모듈 구조)
- **도메인 구성**: user, product, category, cart, wishlist, order, coupon, point, review, search, inventory
- **UI/서버**: Thymeleaf 기반 SSR + `/api/**` 엔드포인트 병행
- **운영 관측성**: Spring Actuator + Micrometer Prometheus + Grafana 대시보드 제공

### 코드베이스 스냅샷

- 메인 코드: `src/main/java` 기준 **138개 Java 파일**
- 테스트 코드: `src/test/java` 기준 **89개 Java 파일**
- 템플릿: `src/main/resources/templates` 기준 **30개 HTML 파일**
- DB 스키마: **17개 테이블**, **52개 인덱스** (일반 50 + UNIQUE 2)

---

## 2) 기술 스택

- **Backend**: Spring Boot 3.4.1, Spring Security 6, Spring Data JPA, Validation, Cache, Actuator
- **Language**: Java 21 (toolchain + `options.release=21`)
- **Build**: Gradle Wrapper 9.3.1 (`gradle/wrapper/gradle-wrapper.properties`)
- **Database**: PostgreSQL (로컬/CI 동일 규약은 14.x 기준)
- **Template/UI**: Thymeleaf, Thymeleaf Layout Dialect, Tailwind CSS(CDN)
- **Cache**: Caffeine
- **Observability**: Micrometer Prometheus, Prometheus, Grafana
- **Quality**: Checkstyle, PMD, SpotBugs(옵션), JaCoCo

---

## 3) 핵심 기능

| 영역 | 주요 기능 |
|---|---|
| 회원/인증 | 회원가입, 로그인/로그아웃, 프로필 수정, 비밀번호 변경, 로그인 실패 기반 지연/차단 |
| 상품/카테고리 | 카테고리 트리, 상품 목록/상세, 정렬/필터, 캐시 기반 조회 최적화 |
| 장바구니/위시리스트 | 장바구니 추가/수량 변경/삭제, 위시리스트 토글 |
| 주문 | 주문 생성, 주문 취소, 부분 취소, 반품 요청/승인/거절, 배송 상태/송장 관리 |
| 쿠폰/포인트 | 쿠폰 발급/사용, 포인트 적립/사용/환불/정산 |
| 리뷰 | 리뷰 작성/수정/삭제, 도움돼요 토글, helpful_count 보정 스케줄러 |
| 검색 | PostgreSQL Full-Text Search, 인기 검색어, 검색 로그 비동기 저장/정리 스케줄러 |
| 관리자 | 대시보드, 상품/주문/반품/쿠폰/포인트 관리 |

---

## 4) 패키지 구조

```text
src/main/java/com/shop
├── global
│   ├── config      # Security/Cache/Async/Web 설정
│   ├── security    # 인증/인가, 사용자 조회, 로그인 차단 필터
│   ├── exception   # 비즈니스 예외 및 전역 예외 처리
│   ├── common,dto  # 공통 응답/페이지네이션 객체
│   └── event       # 도메인 이벤트
└── domain
    ├── user
    ├── product
    ├── category
    ├── cart
    ├── wishlist
    ├── order
    ├── coupon
    ├── point
    ├── review
    ├── search
    └── inventory
```

---

## 5) 운영/설계 포인트

아래는 빠른 이해를 위한 요약이며, 구현 세부 규칙과 배경은 링크된 문서에서 관리합니다.

- **동시성 안전성**: 주문/취소/반품 처리 시 상태 전이 검증 및 락 기반 제어로 오버셀/경합 이슈를 완화합니다.
- **도메인 분리**: 서비스 파사드 + 하위 전문 서비스 분리(주문 생성/취소/조회/부분취소 등)로 변경 영향 범위를 축소합니다.
- **성능 최적화**:
  - HikariCP 풀 크기 명시(17)
  - JPA batch/fetch 설정
  - Caffeine 캐시(홈/카테고리/상품/검색/리뷰/로그인시도/쿠폰 등 세분화)
- **보안 경계**:
  - `/api/**`와 웹 보안 체인 분리
  - 웹 폼 CSRF 활성화, API CSRF 비활성화
  - Actuator 노출 최소화(health/prometheus 외 관리자 권한)
- **비동기 처리**:
  - 검색 로그 저장 비동기화(`@Async`) 
  - graceful shutdown 시 비동기 작업 종료 대기
- **정기 작업(Scheduler)**:
  - 검색 로그 보존 기간 기반 배치 삭제
  - 리뷰 helpful_count 정합성 보정
  - 사용자 연간 구매액 기반 등급 재산정

세부 문서:

- `docs/implementation-details.md`: 아키텍처/주문 처리/데이터 모델의 구현 상세
- `docs/order-invariant-checks.md`: 주문 무결성 점검 규칙
- `docs/search-log-ops-policy.md`: 검색 로그 운영 정책
- `docs/adr/ADR-0001-tier-criteria-cumulative-total-spent.md`: 등급 산정 기준(누적 `total_spent`) 결정 배경
- `docs/adr/ADR-0002-point-accrual-on-delivery-and-cancel-policy.md`: 포인트 적립 시점/취소·반품 정산 정책

---

## 6) 로컬 실행 가이드

### 6-1. 사전 요구사항

- JDK 21
- PostgreSQL 14.x
- (선택) Docker / Docker Compose (모니터링 스택 실행 시)

### 6-2. 데이터베이스 생성

```sql
CREATE DATABASE shopping_mall_db;
```

### 6-3. 스키마 준비

이 프로젝트는 실행 시 스키마를 자동 생성하지 않습니다.

- `spring.jpa.hibernate.ddl-auto=validate`
- `spring.sql.init.mode=never`

따라서 아래 SQL을 먼저 적용하세요.

- 기본 스키마: `src/main/resources/schema.sql`
- 추가 변경 스크립트: `src/main/resources/migration/*.sql`, `src/main/resources/sql/*.sql`

### 6-4. 애플리케이션 설정

`src/main/resources/application.yml`의 DB 접속 정보를 환경에 맞게 수정하세요.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shopping_mall_db?stringtype=unspecified
    username: postgres
    password: 4321
```

### 6-5. 실행

```bash
./gradlew bootRun
```

- 앱 접속: http://localhost:8080
- 메트릭: http://localhost:8080/actuator/prometheus

---

## 7) 테스트 & 품질 검증

### 7-1. 테스트 DB 초기화(권장)

```bash
PGPASSWORD="$TEST_DB_PASSWORD" psql \
  -h localhost -p 5432 \
  -U "$TEST_DB_USERNAME" \
  -d shopping_mall_db \
  -v ON_ERROR_STOP=1 \
  -c "DROP SCHEMA IF EXISTS public CASCADE;" \
  -c "CREATE SCHEMA public;"
```

환경변수 기본 규약:

- `TEST_DB_URL`
- `TEST_DB_USERNAME`
- `TEST_DB_PASSWORD`

### 7-2. 주요 명령

```bash
./gradlew test
./gradlew check
./gradlew jacocoTestReport
./gradlew jacocoTestCoverageVerification
./gradlew spotbugsMain spotbugsTest -PenableSpotbugs=true
```

### 7-3. 아키텍처 규칙 점검 스크립트

도메인 간 양방향 의존(서비스/레포지토리 레이어)을 감지하는 스크립트를 제공합니다.

```bash
./scripts/check-domain-dependencies.sh
```

### 7-4. 문서 수치 검증/갱신

README에 적힌 코드/스키마 수치는 아래 스크립트로 검증합니다.

```bash
./scripts/validate-doc-stats.sh
```

- 출력: Markdown 표(`실제값` vs `문서값`)
- 실패 조건: README 수치와 실제 리포지토리 수치가 다르면 non-zero 종료

수치 갱신 방법:
1. 스크립트를 실행해 실제 수치를 확인합니다.
2. `README.md`의 `코드베이스 스냅샷` 값을 실제값으로 수정합니다.
3. 다시 스크립트를 실행해 모두 `✅ 일치`인지 확인합니다.

---

## 8) 모니터링 스택 (Prometheus/Grafana)

1) 애플리케이션 실행

```bash
./gradlew bootRun
```

2) 모니터링 컨테이너 실행

```bash
docker compose -f monitoring/docker-compose.monitoring.yml up -d
```

3) 접속

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin / admin)

기본 대시보드 JSON은 `monitoring/grafana/dashboards/shopping-mall-overview.json`에 포함되어 있습니다.

---

## 9) 참고 문서

- `NEW_HIRE_GUIDE.md`: 온보딩 및 학습 순서
- `docs/implementation-details.md`: 구현 상세(README 요약 항목의 상세 기준)
- `docs/order-invariant-checks.md`: 주문 무결성 점검 규칙
- `docs/search-log-ops-policy.md`: 검색 로그 운영 정책
- `docs/adr/template.md`: ADR 작성 템플릿
- `docs/adr/ADR-0001-tier-criteria-cumulative-total-spent.md`: 등급 산정 기준 ADR
- `docs/adr/ADR-0002-point-accrual-on-delivery-and-cancel-policy.md`: 포인트 정산/취소 정책 ADR
- `load-test-analysis.md`, `thymeleaf-optimization-analysis.md`: 성능/최적화 분석 문서
- `docs/archive/shopmall-phase0-report.md`: 프로젝트 0단계 회고/상세 보고서(아카이브)

# 🛒 Shopping Mall - Spring Boot E-Commerce Application

대규모 트래픽과 동시성 이슈를 고려해 설계한 **Spring Boot 기반 이커머스 웹 애플리케이션**입니다.

## 기술 스택

- **Backend**: Spring Boot 3.4.1, Spring Security 6, Spring Data JPA, Spring Validation, Spring Cache, Actuator, Micrometer Prometheus
- **Language/Build**: Java Toolchain 25 (컴파일 타깃 17), Gradle 8.12
- **Database**: PostgreSQL
- **View**: Thymeleaf, Thymeleaf Layout Dialect, Tailwind CSS(CDN)
- **Cache**: Caffeine

## 핵심 기능

| 영역 | 기능 |
|---|---|
| 회원/인증 | 회원가입, 로그인/로그아웃, 프로필 수정, 비밀번호 변경, 로그인 시도 제한 |
| 상품/카테고리 | 3단계 카테고리, 상품 목록/상세, 정렬·필터·검색 |
| 장바구니/위시리스트 | 장바구니 추가·수정·삭제, 위시리스트 토글 |
| 주문 | 주문 생성, 주문 취소, 부분 취소, 반품 요청/관리, 배송비 서버 정책 계산 |
| 쿠폰/포인트 | 쿠폰 등록/보유 쿠폰 조회, 포인트 조회/관리 |
| 리뷰 | 리뷰 작성·수정·삭제, 도움돼요 토글 |
| 검색 | PostgreSQL Full-Text Search, 인기 검색어 기록 |
| 관리자 | 대시보드, 상품/주문/반품/쿠폰/포인트 관리 |

## 프로젝트 구조

```text
com.shop
├── global
│   ├── config      # Security/Cache/Web/Async 설정
│   ├── security    # 인증/인가, 사용자 주입, 로그인 제한
│   ├── exception   # 비즈니스 예외, 전역 예외 처리
│   ├── common|dto  # 공통 응답/페이징 객체
│   └── event       # 도메인 이벤트
└── domain
    ├── user        # 회원/등급/마이페이지
    ├── product     # 상품/이미지/조회수/관리자 상품 관리
    ├── category    # 카테고리 트리
    ├── cart        # 장바구니
    ├── wishlist    # 위시리스트
    ├── order       # 주문/부분취소/반품
    ├── coupon      # 쿠폰/사용자 쿠폰
    ├── point       # 포인트 조회/관리
    ├── review      # 리뷰/도움돼요
    ├── inventory   # 재고 이력
    └── search      # 검색/검색 로그
```

## 아키텍처 & 운영 포인트

- **순환 의존성 방지**: 도메인 간 단방향 의존을 유지해 결합도를 낮췄습니다.
- **동시성 제어**: 주문/재고 처리 구간에서 비관적 락과 상태 검증으로 오버셀을 방지합니다.
- **성능 최적화**: JPA batch 설정, fetch size, 캐시 적용, 인덱스 중심 쿼리 설계를 반영했습니다.
- **운영 관측성**: Actuator(`health`, `info`, `metrics`, `caches`, `prometheus`) 노출 및 메트릭 로깅 지원.
- **보안 강화**: CSRF 적용, 인증/권한 분리, 로그인 실패 지연/차단 정책 반영.

## 실행 방법

### 1) 사전 요구사항

- JDK 25+
- PostgreSQL 14+

> 이 프로젝트는 **Java 25 런타임**을 사용하고, 컴파일 타깃(class file)은 **Java 17**로 유지합니다.

### 2) 데이터베이스 생성

```sql
CREATE DATABASE shopping_mall_db;
```

### 3) 스키마 준비

이 프로젝트는 실행 시 스키마 자동 생성을 하지 않습니다.

- `spring.jpa.hibernate.ddl-auto=validate`
- `spring.sql.init.mode=never`

따라서 먼저 아래 SQL을 적용하세요.

- 기본 DDL: `src/main/resources/schema.sql`
- 추가 마이그레이션: `src/main/resources/migration/*.sql`, `src/main/resources/sql/*.sql`

`schema.sql`을 **기본 스키마의 단일 기준(source of truth)** 으로 사용하고,
테이블 선언 순서는 `orders → order_items → carts` 흐름을 유지합니다.

### 4) 애플리케이션 설정

`src/main/resources/application.yml`에서 DB 정보를 환경에 맞게 수정합니다.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shopping_mall_db?stringtype=unspecified
    username: postgres
    password: 4321
```

### 5) 실행

```bash
./gradlew bootRun
```

접속 주소: http://localhost:8080

## Prometheus/Grafana 연동

기본 Actuator 메트릭은 `/actuator/prometheus`로 노출되며, 로컬에서 Prometheus/Grafana를 바로 붙일 수 있습니다.

### 1) 앱 실행

```bash
./gradlew bootRun
```

### 2) 모니터링 스택 실행

```bash
docker compose -f monitoring/docker-compose.monitoring.yml up -d
```

### 3) 접속

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (기본 계정 `admin` / `admin`)

Grafana에서 데이터소스에 Prometheus(`http://prometheus:9090`)를 추가하면 대시보드를 구성할 수 있습니다.

## 테스트

```bash
./gradlew test
```

특정 테스트만 실행하려면:

```bash
./gradlew test --tests com.shop.global.cache.CacheKeyGeneratorTest
```

## 캐시 정책 (요약)

실제 운영 캐시 TTL/사이즈는 `CacheConfig` 기준입니다.

- 홈 집계(`bestSellers`, `newArrivals`, `deals`, `topCategories`, `popularKeywords`): 1분
- 카테고리 계층 캐시(`subCategories`, `categoryDescendants`, `categoryBreadcrumb`, `categoryById`): 30분
- 상품 목록/검색(`productList`, `searchResults`, `categoryProducts`): 2분
- 상품 상세(`productDetail`): 2분
- 리뷰(`productReviews`): 30초
- 인증/보안(`userDetails`, `loginAttempts`), 쿠폰(`activeCoupons`): 짧은 TTL 운영

## 보안/운영 메모

- `POST` 폼에는 반드시 CSRF hidden input을 포함해야 합니다.
- `security.login-attempt.trusted-proxy-cidrs`, `trusted-hop-count`는 로그인 제한과 검색 로그 IP 해석에 함께 사용됩니다.
- 운영 로그 포맷(`event=login_fail`, `event=login_blocked`)은 대시보드 호환을 위해 유지하세요.

## 참고 문서

- `NEW_HIRE_GUIDE.md`: 온보딩 가이드
- `프로젝트_상세_설명서.md`: 전체 설계 설명
- `docs/`: 주문 불변식, 검색 로그 정책, 성능 분석 등 운영 문서


## Prometheus / Grafana 연동

### `/actuator/prometheus` 문자열을 그대로 써야 하나요?

아니요. `/actuator/prometheus`의 출력은 **Prometheus Exposition Format(수집용 원시 지표)** 입니다.
애플리케이션 코드나 화면에서 이 문자열을 직접 해석해 쓰기보다,
**Prometheus가 수집 → Recording Rule로 KPI 가공 → Grafana 대시보드 시각화** 흐름으로 사용해야 합니다.

이 프로젝트에는 위 흐름을 바로 사용할 수 있도록 아래 파일을 포함했습니다.

- `monitoring/prometheus/prometheus.yml`
- `monitoring/prometheus/rules/shopping-mall-recording-rules.yml`
- `monitoring/grafana/dashboards/shopping-mall-overview.json`
- `monitoring/docker-compose.yml`

### Actuator 메트릭 엔드포인트

- 공개 헬스체크: `/actuator/health`
- Prometheus 스크래핑: `/actuator/prometheus`
- 나머지 `/actuator/**`: `ADMIN` 권한 필요

또한 p95 latency 집계를 위해 HTTP 서버 메트릭 히스토그램을 활성화했습니다.

### 실행 방법 (로컬)

1. 애플리케이션 실행 (`localhost:8080`)
2. 모니터링 스택 실행

```bash
cd monitoring
docker compose up -d
```

3. 접속
   - Prometheus: `http://localhost:9090`
   - Grafana: `http://localhost:3000` (`admin` / `admin`)

Grafana는 시작 시 `Shopping Mall Overview` 대시보드를 자동 로드합니다.

### 제공 KPI(Recording Rule)

- `shopping_mall:http_rps:rate1m`
- `shopping_mall:http_error_rate:ratio1m`
- `shopping_mall:http_p95_seconds:route`
- `shopping_mall:jvm_heap_used_bytes`
- `shopping_mall:hikaricp_active_connections`

운영 환경에서는 `/actuator/prometheus`에 대해 보안 그룹/방화벽 등 네트워크 접근 제어를 함께 적용하세요.

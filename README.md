# 🛒 Shopping Mall - Spring Boot E-Commerce Application

대규모 트래픽과 동시성을 고려한 풀스택 이커머스 웹 애플리케이션입니다.

## 기술 스택

- **Backend**: Java 25 런타임 (컴파일 타깃 17), Spring Boot 3.4.1, Spring Security 6, Spring Data JPA
- **Database**: PostgreSQL (1억 건 이상 대응 설계)
- **Frontend**: Thymeleaf + Tailwind CSS (CDN)
- **Build**: Gradle 8.12

## 주요 기능

| 기능 | 설명 |
|------|------|
| 회원관리 | 회원가입, 로그인, 프로필 수정, 비밀번호 변경 |
| 등급시스템 | BRONZE → SILVER → GOLD → PLATINUM → DIAMOND 자동 승급 |
| 상품 | 카테고리(3단계), 상품 목록/상세, 정렬/필터, 전문검색 |
| 장바구니 | 상품 추가/수량 변경/삭제, 실시간 합계 계산 |
| 주문 | 주문 생성(재고 비관적 잠금, 배송비 서버 정책 계산), 주문 내역, 주문 취소 |
| 위시리스트 | 찜하기/해제 토글 |
| 쿠폰 | 쿠폰 코드 등록, 보유 쿠폰 확인 |
| 리뷰 | 상품 리뷰 작성/삭제, 평점 자동 집계 |
| 검색 | PostgreSQL Full-Text Search, 인기 검색어 |
| 관리자 | 대시보드, 주문 관리(상태 변경), 상품 관리 |

## 아키텍처

```
com.shop
├── global
│   ├── config          # WebConfig, SecurityConfig
│   ├── security        # UserDetails, Authentication
│   ├── exception       # BusinessException, GlobalExceptionHandler
│   └── common          # PageResponse
└── domain
    ├── user            # 회원, 등급
    ├── category        # 카테고리 (3단계 계층)
    ├── product         # 상품, 상품이미지
    ├── cart            # 장바구니
    ├── wishlist        # 위시리스트
    ├── order           # 주문, 주문상품
    ├── coupon          # 쿠폰, 사용자쿠폰
    ├── review          # 리뷰
    ├── inventory       # 재고 이력
    └── search          # 검색 로그
```

**순환 의존성 방지**: Order → Cart, Product, User (단방향) / Review → Product (단방향)


- 주문/결제의 **배송비는 클라이언트 입력값을 신뢰하지 않고 서버 정책(등급/주문금액 기준)으로만 계산·저장**됩니다.

## 동시성 & 성능

- **비관적 잠금** (`@Lock(PESSIMISTIC_WRITE)`): 재고 차감 시 동시 주문 방지
- **재고 이력 추적**: 모든 재고 변동을 before/after 스냅샷으로 기록
- **N+1 방지**: `JOIN FETCH` 적극 활용
- **배치 처리**: Hibernate batch fetch size 100, batch insert/update
- **전문 검색**: PostgreSQL tsvector 인덱스 활용
- **50+ 인덱스**: 커버링 인덱스, 복합 인덱스 최적화

## 실행 방법

### 1. 사전 준비

- JDK 25 이상 (Gradle toolchain)

> 참고: 현재 코드베이스는 Java 25로 실행하되, 프레임워크/테스트 라이브러리 호환성을 위해 컴파일 타깃(class file)은 Java 17로 유지합니다.
- PostgreSQL 14 이상

### 2. 데이터베이스 설정

```sql
CREATE DATABASE shopping_mall_db;
```

> 스키마 생성은 애플리케이션 시작 시 자동 실행되지 않습니다. (`spring.jpa.hibernate.ddl-auto=validate`, `spring.sql.init.mode=never`)

### 3. Gradle Wrapper 초기화

프로젝트에 Gradle이 설치되어 있다면:
```bash
gradle wrapper --gradle-version=8.12
```

또는 셋업 스크립트 실행:
```bash
chmod +x setup.sh
./setup.sh
```

### 프록시/미러 환경에서 Gradle 실패 시

사내망/프록시 환경에서 `./gradlew`가 배포본 또는 플러그인 다운로드에 실패하면 아래 순서로 설정하세요.

1. 템플릿 복사
```bash
cp gradle.properties.example gradle.properties
```

2. `gradle.properties`에서 프록시/인증 정보 입력
- `systemProp.http.proxyHost`, `systemProp.http.proxyPort`
- `systemProp.https.proxyHost`, `systemProp.https.proxyPort`
- 필요 시 `systemProp.http.nonProxyHosts`

3. 네트워크 점검
```bash
./gradlew --version
./gradlew test --tests com.shop.global.security.LoginAuthenticationSuccessHandlerTest
```

4. 허용(allowlist) 필요 도메인
- `services.gradle.org` (Gradle distribution)
- `plugins.gradle.org` (Gradle plugins)
- `repo.maven.apache.org` (Maven Central)

> 내부 Artifactory/Nexus를 사용하는 경우, 위 외부 도메인 대신 내부 미러만 허용해도 됩니다.

### 4. 애플리케이션 실행

```bash
./gradlew bootRun
```

실행 전, 아래 **DB 마이그레이션 전략**에 따라 스키마를 먼저 준비해야 합니다.

### 5. 접속

- 메인: http://localhost:8080
- 관리자 로그인: `admin` / `admin!123`
- 회원가입 후 일반 사용자 기능 이용

### DB 설정 변경

`src/main/resources/application.yml`에서 DB 접속 정보를 수정하세요:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shopping_mall_db
    username: postgres
    password: 4321
```




## 캐시 정책 (Source of Truth)

아래 표는 `CacheConfig`(`src/main/java/com/shop/global/config/CacheConfig.java`) 기준 운영 캐시 TTL 정책입니다.

| Cache Name | TTL | Max Size | 비고 |
|---|---:|---:|---|
| `bestSellers` | 1분 | 200 | 홈 집계 |
| `newArrivals` | 1분 | 200 | 홈 집계 |
| `deals` | 1분 | 200 | 홈 집계 |
| `topCategories` | 1분 | 100 | 홈 집계 |
| `popularKeywords` | 1분 | 100 | 홈 집계 |
| `subCategories` | 30분 | 500 | 카테고리 트리 |
| `categoryDescendants` | 30분 | 500 | 카테고리 트리 |
| `categoryBreadcrumb` | 30분 | 500 | 카테고리 트리 |
| `categoryById` | 30분 | 500 | 카테고리 트리 |
| `productList` | 2분 | 300 | 상품 목록/검색 |
| `searchResults` | 2분 | 300 | 상품 목록/검색 |
| `categoryProducts` | 2분 | 300 | 상품 목록/검색 |
| `productDetail` | 2분 | 500 | 상품 상세 |
| `productReviews` | 30초 | 500 | 리뷰 목록 |
| `productReviewVersion` | 60분 | 10000 | 리뷰 버전 관리 |
| `userDetails` | 1분 | 1000 | 인증 사용자 정보 |
| `loginAttempts` | 15분 | 50000 | 로그인 실패 상태 |
| `activeCoupons` | 10초 | 200 | 활성 쿠폰 |

## 운영 보안 설정 (Trusted Proxy)

`security.login-attempt.trusted-proxy-cidrs`와 `security.login-attempt.trusted-hop-count`는 **로그인 차단 IP 계산뿐 아니라 검색 로그(`searchService.logSearch`)의 클라이언트 IP 해석에도 동일하게 적용**됩니다.

- 프록시 미사용 환경: `request.getRemoteAddr()` 기준으로 기록
- 신뢰 프록시 환경: `X-Forwarded-For` / `X-Real-IP`를 trusted proxy CIDR 정책으로 검증 후 기록

운영 환경에서는 실제 L4/L7 프록시 CIDR만 등록하고, hop 수(`trusted-hop-count`)를 인프라 체인과 일치시켜야 합니다.

## 보안 템플릿 규칙

- 모든 Thymeleaf 템플릿의 `method="post"` 폼에는 CSRF hidden input을 **반드시** 포함해야 합니다.
- 표준 삽입 구문: `<input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>`
- 신규 폼 추가/수정 시 누락 여부를 코드 리뷰 체크리스트에 포함하세요.

## 프로젝트 구조

```
shopping-mall/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── setup.sh
├── src/main/
│   ├── java/com/shop/
│   │   ├── ShopApplication.java
│   │   ├── global/         (config, security, exception)
│   │   └── domain/         (user, product, order, cart, ...)
│   └── resources/
│       ├── application.yml
│       ├── schema.sql      (DDL - 테이블, 인덱스)
│       └── templates/       (Thymeleaf 22개 템플릿)
└── README.md
```

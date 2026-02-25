# 🛒 Shopping Mall - Spring Boot E-Commerce Application

대규모 트래픽과 동시성을 고려한 풀스택 이커머스 웹 애플리케이션입니다.

## 기술 스택

- **Backend**: Java 17, Spring Boot 3.4.1, Spring Security 6, Spring Data JPA
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
| 주문 | 주문 생성(재고 비관적 잠금), 주문 내역, 주문 취소 |
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

## 동시성 & 성능

- **비관적 잠금** (`@Lock(PESSIMISTIC_WRITE)`): 재고 차감 시 동시 주문 방지
- **재고 이력 추적**: 모든 재고 변동을 before/after 스냅샷으로 기록
- **N+1 방지**: `JOIN FETCH` 적극 활용
- **배치 처리**: Hibernate batch fetch size 100, batch insert/update
- **전문 검색**: PostgreSQL tsvector 인덱스 활용
- **50+ 인덱스**: 커버링 인덱스, 복합 인덱스 최적화

## 실행 방법

### 1. 사전 준비

- JDK 17 이상
- PostgreSQL 14 이상

### 2. 데이터베이스 설정

```sql
CREATE DATABASE shopping_mall_db;
```

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

### 4. 애플리케이션 실행

```bash
./gradlew bootRun
```

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
│       ├── data.sql         (초기 데이터)
│       └── templates/       (Thymeleaf 22개 템플릿)
└── README.md
```

# 신입 온보딩 가이드 (shopping-mall)

## 1) 전체 구조 한눈에 보기
- 이 프로젝트는 **Spring Boot + JPA + Thymeleaf** 기반의 모놀리식 이커머스 서비스입니다.
- 코드 구조는 `global`(공통) + `domain`(비즈니스 도메인)으로 분리되어 있으며, 도메인별로 `controller/service/repository/entity` 레이어를 따릅니다.

## 2) 디렉터리/레이어 규칙
- `src/main/java/com/shop/global`
  - `config`: 보안/캐시/Web 설정
  - `security`: 인증 사용자 주입, 현재 사용자 조회
  - `exception`: 비즈니스 예외와 전역 예외 처리
- `src/main/java/com/shop/domain/*`
  - 각 도메인(User, Product, Order...)에서 `Controller -> Service -> Repository` 흐름으로 동작
- `src/main/resources/templates`
  - Thymeleaf 템플릿(서버사이드 렌더링)
- `src/main/resources/schema.sql`
  - 핵심 DDL 및 인덱스 정의

## 3) 신입이 먼저 이해해야 할 핵심 포인트
1. **트랜잭션/동시성**
   - 주문 생성/취소 시 재고와 주문 상태를 함께 다루므로 동시성 제어가 가장 중요합니다.
   - `OrderService`에서 비관적 락과 자원 획득 순서 정렬을 활용해 오버셀·데드락을 방지합니다.
2. **보안 경계**
   - 인증 없는 공개 경로와 인증 필수 경로가 `SecurityConfig`에 명확히 분리되어 있습니다.
   - 관리자 기능은 `ROLE_ADMIN`으로 보호됩니다.
3. **도메인 모델 이해**
   - `users`, `products`, `orders`, `order_items`, `carts`, `user_coupons`, `reviews`, `search_logs`, `product_inventory_history`가 핵심 테이블입니다.
4. **성능 지향 설계**
   - 배치 fetch, JDBC batch, 캐시(Caffeine), DB 인덱스 중심 설계가 반영되어 있습니다.

## 4) 추천 학습 순서
1. `README.md`로 기능/아키텍처/실행 방법 파악
2. `SecurityConfig`와 `SecurityUtil`로 인증 흐름 파악
3. `OrderService`로 트랜잭션 핵심 흐름(주문/취소) 이해
4. `ProductService`, `CartService`, `CouponService`로 주변 도메인 연결 이해
5. `schema.sql`로 도메인-DB 매핑과 제약 조건 확인
6. 동시성 테스트(`OrderOversellingTest`, `OrderDeadlockTest` 등)로 실제 위험 시나리오 학습

## 5) 첫 1주 실전 체크리스트
- 로컬에서 애플리케이션 실행 후 주문 생성/취소 시나리오를 직접 수행한다.
- 테스트 클래스를 읽고, 테스트 데이터 준비/복구 패턴을 이해한다.
- 작은 수정(예: 목록 정렬 조건 추가)을 통해 Controller-Service-Repository 전 구간 디버깅을 경험한다.

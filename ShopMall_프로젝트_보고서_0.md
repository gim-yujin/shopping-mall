# ShopMall 프로젝트 상세 보고서

> **프로젝트명:** ShopMall — 대규모 쇼핑몰 웹 애플리케이션  
> **기술 스택:** Spring Boot 3.4.1 · Java 17 · PostgreSQL 14+ · Thymeleaf · Tailwind CSS  
> **프로젝트 기간:** 2026년 2월 7일 ~ 2월 11일  
> **문서 작성일:** 2026년 2월 11일

---

## 1. 프로젝트 개요

ShopMall은 성능 최적화 학습을 목적으로 설계된 대규모 이커머스 웹 애플리케이션이다. 1억 건 이상의 레코드 처리를 상정하고 설계한 PostgreSQL 스키마를 기반으로, Spring Boot 풀스택 아키텍처 위에서 실질적인 쇼핑몰 비즈니스 로직을 구현했다. 단순한 CRUD를 넘어 회원 등급 시스템, 쿠폰 할인, 주문 트랜잭션, 검색, 캐싱, 인덱스 최적화 등 실전 수준의 기능과 성능 최적화를 단계적으로 적용해 나간 프로젝트이다.

### 1.1 핵심 목표

- 대규모 데이터(1억+ 레코드)를 전제로 한 DB 스키마 설계 및 인덱스 전략 수립
- Spring Boot 기반 풀스택 MVC 아키텍처 구현 (Controller → Service → Repository → Entity)
- 주문 생성이라는 단일 트랜잭션이 7개 테이블을 원자적으로 변경하는 복합 비즈니스 로직 구현
- 캐시, 인덱스, 페이지네이션 등 다층적 성능 최적화 적용

### 1.2 기술 스택 상세

| 계층 | 기술 | 버전 |
|------|------|------|
| Framework | Spring Boot | 3.4.1 |
| Language | Java | 17 |
| Database | PostgreSQL | 14+ |
| Template Engine | Thymeleaf + Layout Dialect | - |
| CSS | Tailwind CSS (CDN) | 3.x |
| Security | Spring Security 6 | - |
| Cache | Spring Cache + Caffeine | 3.1.8 |
| Build Tool | Gradle | Kotlin DSL |

---

## 2. 데이터베이스 설계

### 2.1 스키마 구조

프로젝트 시작 전 성능 최적화를 고려하여 직접 설계한 PostgreSQL 스키마로, 15개 테이블과 50개 이상의 인덱스로 구성되어 있다. 각 테이블에는 예상 레코드 수를 주석으로 명시하여 대규모 환경을 전제로 설계했다.

```
user_tiers (등급 마스터)
    └── users (100만 명)
            ├── user_tier_history (500만 건) — 등급 변경 이력
            ├── orders (2,000만 건)
            │       └── order_items ⭐ (1억 건) — 핵심 대용량 테이블
            ├── carts (500만 건)
            ├── wishlists (1,000만 건)
            ├── user_coupons (5,000만 건)
            ├── reviews ⭐ (5,000만 건)
            └── search_logs ⭐ (5,000만 건)

categories (1,000개, 3-depth 계층)
    └── products (100만 개)
            ├── product_images (300만 건)
            └── product_inventory_history ⭐ (1억 건) — 재고 변동 이력

coupons (10만 개) — 쿠폰 마스터
```

### 2.2 스키마 설계 특징

**데이터 무결성 강화:** 모든 테이블에 CHECK 제약조건을 적용했다. 예를 들어 `users` 테이블의 `total_spent >= 0`, `point_balance >= 0`, `role IN ('ROLE_USER', 'ROLE_ADMIN')` 등의 제약을 DB 레벨에서 보장한다. `user_coupons`에는 `is_used`와 `used_at`, `order_id`의 정합성을 보장하는 복합 CHECK 제약이 있다.

**비정규화 전략:** `order_items` 테이블에 `product_name`과 `unit_price`를 스냅샷으로 저장하여, 상품 정보가 변경되더라도 주문 당시 정보를 보존한다. `products` 테이블에 `rating_avg`, `review_count`, `sales_count`를 반정규화하여 조회 성능을 최적화했다.

**3-depth 카테고리 계층:** `categories` 테이블이 `parent_category_id`를 통한 자기 참조로 대분류(level 1) → 중분류(level 2) → 소분류(level 3) 구조를 구현한다. `level BETWEEN 1 AND 3` CHECK 제약으로 depth를 제한했다.

**JSONB 활용:** `reviews.images` 컬럼에 PostgreSQL JSONB 타입을 적용하여 이미지 URL 배열을 유연하게 저장한다.

### 2.3 인덱스 전략

총 50개 이상의 인덱스를 설계했으며, 크게 4가지 유형으로 분류된다.

**1) 단순 B-Tree 인덱스** — 기본적인 조회 최적화
```sql
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_order_date ON orders(order_date DESC);
```

**2) 복합 인덱스 (Composite Index)** — 다중 조건 쿼리 최적화
```sql
-- WHERE is_active = true ORDER BY sales_count DESC 패턴에 최적
CREATE INDEX idx_product_sales ON products(is_active, sales_count DESC);
CREATE INDEX idx_product_created ON products(is_active, created_at DESC);
CREATE INDEX idx_product_category ON products(category_id, is_active, sales_count DESC);
```
복합 인덱스 설계 원칙: WHERE 절의 등치 조건 컬럼을 선두에, ORDER BY 절의 컬럼을 후미에 배치하여 인덱스 스캔만으로 정렬을 처리한다.

**3) 커버링 인덱스 (Covering Index)** — Index-Only Scan 유도
```sql
-- 상품별 판매 통계 쿼리에서 테이블 접근 없이 인덱스만으로 응답
CREATE INDEX idx_order_items_covering 
    ON order_items(product_id, created_at) 
    INCLUDE (quantity, subtotal);
```

**4) Partial Expression Index** — 조건부 연산 인덱스
```sql
-- 할인 상품 정렬: (original_price - price) 연산을 사전 계산
-- is_active=true인 행만 인덱싱하여 인덱스 크기를 대폭 축소
CREATE INDEX idx_product_deals ON products ((original_price - price) DESC)
    WHERE is_active = true AND original_price IS NOT NULL AND original_price > price;
```
100만 상품 중 할인 상품이 10만 개라면, 전체가 아닌 10만 행만 인덱싱된다. 연산식까지 사전 계산되므로 쿼리 시 `(original_price - price)`를 매 행마다 계산할 필요가 없다.

**5) GIN (Generalized Inverted Index)** — 전문 검색 최적화
```sql
CREATE INDEX idx_product_name_gin ON products USING gin(to_tsvector('simple', product_name));
CREATE INDEX idx_review_content_gin ON reviews USING gin(to_tsvector('simple', content));
```

---

## 3. 애플리케이션 아키텍처

### 3.1 패키지 구조

Package-by-Feature 패턴을 채택하여 10개 도메인을 독립적으로 관리한다. 총 67개의 Java 파일로 구성되며, 각 도메인은 controller, entity, repository, service 계층을 포함한다.

```
com.shop
├── ShopApplication.java              # @EnableScheduling
├── domain
│   ├── cart/         (Controller, Entity, Repository, Service)
│   ├── category/     (Controller, Entity, Repository, Service)
│   ├── coupon/       (Controller, Entity×2, Repository×2, Service)
│   ├── inventory/    (Entity, Repository, Service)
│   ├── order/        (Controller, DTO, Entity×2, Repository×2, Service)
│   ├── product/      (Controller×3, Entity×2, Repository×2, Service)
│   ├── review/       (Controller, DTO, Entity, Repository, Service)
│   ├── search/       (Controller, Entity, Repository, Service)
│   ├── user/         (Controller×2, DTO, Entity×3, Repository×3, Service, Scheduler)
│   └── wishlist/     (Controller, Entity, Repository, Service)
└── global
    ├── common/       (PageResponse)
    ├── config/       (CacheConfig, SecurityConfig, WebConfig)
    ├── exception/    (BusinessException, GlobalExceptionHandler, ...)
    └── security/     (CustomUserDetailsService, CustomUserPrincipal, SecurityUtil)
```

### 3.2 템플릿 구조

Thymeleaf Layout Dialect를 활용한 22개의 HTML 템플릿으로 구성된다.

```
templates/
├── layout/default.html      # 공통 레이아웃 (헤더, 네비게이션, 푸터)
├── home.html                # 메인 페이지 (베스트셀러, 신상품, 할인상품)
├── fragments/product-card.html  # 상품 카드 + 페이지네이션 fragment
├── auth/     login.html, signup.html
├── product/  list.html, detail.html, search.html
├── order/    checkout.html, detail.html, list.html
├── cart/     index.html
├── coupon/   index.html
├── wishlist/ index.html
├── mypage/   index.html, profile.html, reviews.html
├── admin/    dashboard.html, orders.html, products.html
└── error/    404.html, 500.html
```

### 3.3 보안 설정

Spring Security 6 기반으로 URL별 접근 권한을 관리한다. 인증되지 않은 사용자는 상품 조회, 검색 등 공개 페이지만 접근 가능하며, 장바구니·주문·마이페이지 등은 로그인이 필요하다. `/admin/**` 경로는 `ROLE_ADMIN` 권한이 필요하다. CSRF 보호가 활성화되어 있으며 모든 POST 폼에 CSRF 토큰을 포함한다.

---

## 4. 핵심 비즈니스 로직

### 4.1 주문 생성 (Order Creation) — 7개 테이블 원자적 변경

이 프로젝트에서 가장 복잡한 비즈니스 로직은 주문 생성이다. `POST /orders` 하나의 HTTP 요청이 단일 `@Transactional` 안에서 7개 테이블을 원자적으로 변경한다.

**전체 데이터 흐름:**

```
[HTTP POST /orders]
       │
       ▼
[Spring Security]  CSRF 검증 → 인증 확인
       │
       ▼
[OrderController]  userId 추출 → orderService.createOrder() 호출
       │
       ▼
[OrderService @Transactional]  ← 여기서부터 단일 트랜잭션
       │
       ├─ Step 0: 사용자 + 등급 로드
       │   cartRepository.findByUserIdWithProduct(userId)
       │   userRepository.findById(userId)  → User + UserTier (EAGER)
       │   등급 혜택 추출: discountRate, pointEarnRate, freeShippingThreshold
       │
       ├─ Step 1: 재고 차감 + 금액 계산 (for each cart item)
       │   productRepository.findByIdWithLock(productId)  ← SELECT ... FOR UPDATE
       │   product.decreaseStock(quantity)       ← [products] UPDATE
       │   등급 할인 계산: subtotal × tierDiscountRate / 100
       │   inventoryHistoryRepository.save(...)  ← [product_inventory_history] INSERT
       │
       ├─ Step 2: 쿠폰 할인 (optional)
       │   userCouponRepository.findById(userCouponId)
       │   coupon.calculateDiscount(afterTierAmount)  ← PERCENT/FIXED
       │   totalDiscount = tierDiscount + couponDiscount
       │
       ├─ Step 3: 배송비 계산
       │   tier.freeShippingThreshold == 0 OR totalAmount >= threshold → 무료
       │   else → 3,000원
       │
       ├─ Step 4: 주문 엔티티 생성 + 저장
       │   finalAmount = totalAmount - totalDiscount + shippingFee
       │   order.markPaid()
       │   orderRepository.save(order)  ← [orders] + [order_items] INSERT (cascade)
       │
       ├─ Step 5: 쿠폰 사용 처리
       │   userCoupon.use(orderId)  ← [user_coupons] UPDATE
       │
       ├─ Step 6: 포인트 적립 + 누적금액 갱신
       │   user.addTotalSpent(finalAmount)     ← [users] UPDATE
       │   earnedPoints = finalAmount × tier.pointEarnRate / 100
       │   user.addPoints(earnedPoints)        ← [users] UPDATE
       │
       ├─ Step 7: 등급 재산정
       │   userTierRepository.findFirstByMinSpentLessThanEqual...
       │   user.updateTier(newTier)            ← [users] UPDATE (if changed)
       │
       └─ Step 8: 장바구니 비우기
           cartRepository.deleteByUserId(userId)  ← [carts] DELETE
       │
       ▼
[Hibernate Flush]  Dirty Checking → 7개 테이블 UPDATE/INSERT/DELETE
       │
       ▼
[COMMIT]  성공 시 7개 테이블 동시 반영 / 실패 시 전체 ROLLBACK
       │
       ▼
[302 Redirect → GET /orders/{id}]  PRG 패턴
```

**변경되는 7개 테이블:**

| 순서 | 테이블 | 변경 유형 | 내용 |
|------|--------|----------|------|
| 1 | `products` | UPDATE | stock_quantity 감소, sales_count 증가 |
| 2 | `product_inventory_history` | INSERT | 재고 변동 이력 (change_type='OUT') |
| 3 | `orders` | INSERT | 주문 헤더 |
| 4 | `order_items` | INSERT | 주문 상세 (cascade) |
| 5 | `user_coupons` | UPDATE | is_used=true, used_at, order_id |
| 6 | `users` | UPDATE | total_spent, point_balance, tier_id |
| 7 | `carts` | DELETE | 사용자 장바구니 전체 삭제 |

**동시성 제어:** `SELECT ... FOR UPDATE` (비관적 락)으로 재고 차감 시 Race Condition을 방지한다. 재고 부족 시 `InsufficientStockException`이 발생하며 전체 트랜잭션이 ROLLBACK된다.

### 4.2 주문 취소 (Order Cancellation) — 모든 사이드 이펙트 역전

주문 취소는 생성의 역과정으로, 모든 사이드 이펙트를 원상복구해야 한다.

```
cancelOrder(orderId)
  ├─ order.cancel()                        → 주문 상태 CANCELLED, cancelledAt 기록
  ├─ product.increaseStock(quantity)        → 재고 복구
  ├─ inventoryHistory(change_type='IN')     → 재고 변동 이력 (입고)
  ├─ user.addTotalSpent(finalAmount.negate()) → 누적금액 차감 (floor at 0)
  ├─ user.addPoints(-earnedPoints)          → 적립 포인트 회수 (floor at 0)
  ├─ userCoupon.cancelUse()                 → 쿠폰 복구 (isUsed=false, usedAt=null, orderId=null)
  └─ tierRepository → user.updateTier()     → 등급 재산정 (강등 가능)
```

`User.addTotalSpent()`와 `User.addPoints()`에 floor at zero 로직을 적용하여, 취소로 인해 누적금액이나 포인트가 음수가 되는 것을 방지한다.

### 4.3 회원 등급 시스템 (User Tier System)

5단계 등급 체계에 등급별 차등 혜택을 제공한다.

| 등급 | 레벨 | 최소 누적금액 | 할인율 | 포인트 적립률 | 무료배송 기준 |
|------|------|-------------|--------|-------------|-------------|
| 웰컴 | 1 | 0원 | 0% | 1.0% | 50,000원 |
| 실버 | 2 | 500,000원 | 2% | 1.0% | 30,000원 |
| 골드 | 3 | 2,000,000원 | 5% | 1.5% | 20,000원 |
| VIP | 4 | 5,000,000원 | 7% | 2.0% | 무료 |
| VVIP | 5 | 10,000,000원 | 10% | 3.0% | 무료 |

**등급 혜택 적용 흐름 (체크아웃):**
1. 등급 할인: 상품 소계 × 등급 할인율
2. 쿠폰 할인: (등급 할인 적용 후 금액)에 대해 쿠폰 할인 적용
3. 배송비: 등급별 무료배송 기준금액 이상이면 무료, 미만이면 3,000원
4. 포인트 적립: 최종 결제금액 × 등급 포인트 적립률

**연간 등급 재산정 (TierScheduler):**

매년 1월 1일 00:00에 Spring Scheduler가 자동 실행되어 전년도 주문금액 기준으로 전체 회원의 등급을 재산정한다.

```
@Scheduled(cron = "0 0 0 1 1 *")  // 매년 1월 1일 00:00
recalculateTiers()
  ├─ 전년도(1/1 ~ 12/31) 취소 제외 주문금액 집계
  │   SELECT user_id, SUM(final_amount) GROUP BY user_id
  ├─ 전체 회원 순회
  │   ├─ totalSpent = 전년도 주문금액으로 갱신 (누적 리셋)
  │   ├─ 전년도 금액 기준 새 등급 결정
  │   └─ 변경 시 user_tier_history에 이력 저장
  └─ 로그: "승급 12명, 강등 5명, 유지 83명"
```

### 4.4 쿠폰 시스템

**쿠폰 유형:**
- `FIXED`: 정액 할인 (예: 5,000원 할인)
- `PERCENT`: 정률 할인 (예: 10% 할인, max_discount로 상한 제한)

**발급 방식 (2가지):**
1. 쿠폰 코드 입력: `POST /coupons/issue` (couponCode 파라미터)
2. 발급 버튼 클릭: `POST /coupons/issue/{couponId}` (쿠폰 카드의 "쿠폰 받기" 버튼)

두 방식 모두 내부적으로 동일한 `issueToUser()` private 메서드를 호출하며, 유효성 검사(유효기간, 수량) + 중복 발급 방지를 수행한다. 쿠폰 페이지에서는 이미 발급받은 쿠폰에 "발급완료" 라벨이 표시된다.

### 4.5 리뷰 시스템

- 주문 상품에 대해 리뷰 작성 (중복 작성 방지: `uk_review_user_order_item`)
- 리뷰 작성/삭제 시 상품의 `rating_avg`, `review_count` 자동 재계산
- "도움이 돼요" 기능: `POST /reviews/{reviewId}/helpful` (본인 리뷰 셀프 투표 차단)
- 상품 상세 페이지에서 리뷰별 도움이 돼요 버튼 + 카운트 표시

---

## 5. 구현 과정에서 발견·해결한 이슈

프로젝트를 진행하면서 발견한 버그와 누락된 비즈니스 로직을 순차적으로 해결해 나갔다. 아래는 주요 이슈와 해결 과정이다.

### Issue #1: 쿠폰 페이지 성능 문제

**현상:** `/coupons` 접근 시 10만 건의 쿠폰 데이터를 전부 로드하여 10초 이상 소요

**원인:** CouponService와 Repository에 페이지네이션이 구현되어 있었으나, Thymeleaf 템플릿이 `Page` 객체가 아닌 리스트 기반 문법(`#lists.isEmpty()`)을 사용하고 있었고, 페이지네이션 UI가 없었다.

**해결:** 템플릿을 `Page` 객체 호환으로 수정 (`availableCoupons.content`, `availableCoupons.totalElements`), 페이지네이션 네비게이션 UI 추가

### Issue #2: 쿠폰 페이지네이션 T(Math) SpEL 오류

**현상:** `T(Math).max(...)` 표현식에서 Thymeleaf SpEL 파싱 오류

**원인:** `th:with` 내 SpEL에서 `T(Math)` 사용 시 가독성 및 호환성 문제

**해결:** `th:unless="${availableCoupons.first}"` / `th:unless="${availableCoupons.last}"` 방식의 단순 이전/다음 페이지네이션으로 변경

### Issue #3: 결제수단 DB 제약조건 불일치

**현상:** 주문 생성 시 `chk_payment_method` CHECK 제약 위반 오류

**원인:** DB에는 `'CARD', 'BANK', 'KAKAO', 'NAVER', 'PAYCO'`만 허용하는데, 체크아웃 폼에서 다른 값을 전송

**해결:** 체크아웃 폼의 결제수단 옵션을 DB 제약조건과 일치하도록 수정

### Issue #4: 쿠폰-주문 연동 미구현

**현상:** 체크아웃 페이지에 쿠폰 선택 UI가 없고, 주문 생성 시 쿠폰 할인이 적용되지 않음

**원인:** `Coupon.calculateDiscount()` 메서드와 `UserCoupon` 엔티티가 잘 설계되어 있었지만, 주문 플로우에 통합되지 않았음 — 전형적인 "스키마는 있지만 비즈니스 로직 연결이 누락된" 패턴

**해결 (3개 파일):**
- `OrderController.checkoutPage()`: 사용 가능한 쿠폰 목록을 모델에 전달
- `OrderService.createOrder()`: 쿠폰 유효성 검증 + 할인 계산 + 사용 처리 로직 추가
- `checkout.html`: 쿠폰 선택 드롭다운 + JavaScript 실시간 할인 금액 계산 UI 구현

### Issue #5: 주문 취소 시 사용자 통계 미복원

**현상:** 주문 취소 후에도 `totalSpent`와 `pointBalance`가 그대로 유지

**원인:** `cancelOrder()`가 재고 복구와 주문 상태 변경만 수행하고, 사용자 통계(누적금액, 포인트)와 쿠폰 상태를 역전하지 않았음

**해결 (4개 파일):**
- `OrderService.cancelOrder()`: `user.addTotalSpent(finalAmount.negate())`, `user.addPoints(-earnedPoints)`, 쿠폰 `cancelUse()` 호출 추가
- `User.java`: `addTotalSpent()`와 `addPoints()`에 floor at zero 로직 추가
- `UserCoupon.java`: `cancelUse()` 메서드 추가 (isUsed=false, usedAt=null, orderId=null)
- `UserCouponRepository.java`: `findByOrderId(Long orderId)` 쿼리 추가

### Issue #6: 등급 미승급

**현상:** 사용자가 1,000,000원 이상 주문했지만 "웰컴" 등급 유지

**원인:** `OrderService.createOrder()`가 `user.addTotalSpent()`를 호출했지만, 등급 재산정 로직을 트리거하지 않았음. `UserTierRepository`에 적절한 쿼리가 있었지만 어디에서도 호출되지 않는 상태였음.

**해결:** `createOrder()`와 `cancelOrder()` 양쪽에 등급 재산정 코드 추가:
```java
userTierRepository.findFirstByMinSpentLessThanEqualOrderByTierLevelDesc(user.getTotalSpent())
        .ifPresent(user::updateTier);
```

### Issue #7: 등급 혜택 미적용

**현상:** DB에 등급별 `discount_rate`, `point_earn_rate`, `free_shipping_threshold`가 있지만, 코드에서 전부 하드코딩 (1% 포인트, 할인 없음, 배송비 항상 무료)

**원인:** 등급 시스템의 데이터 구조는 완성되어 있었지만, 주문 플로우에서 실제 등급 혜택 값을 참조하지 않고 상수값을 사용

**해결 (3개 파일):**
- `OrderService.createOrder()`: 등급 할인 → 쿠폰 할인 → 배송비 → 포인트 전 과정에 등급 혜택 적용
- `OrderController.checkoutPage()`: 등급 정보를 템플릿에 전달
- `checkout.html`: 등급 배지, 혜택 안내 박스, JavaScript 실시간 계산에 등급 할인율/적립률/무료배송 기준 반영

### Issue #8: 메인 페이지 카테고리 중복

**현상:** 같은 카테고리명이 다른 ID로 여러 번 표시, 클릭 시 "상품이 없습니다"

**원인:** `application.yml`의 `sql.init.mode: always` 설정으로 `data.sql`이 서버 재시작 때마다 실행. `ON CONFLICT DO NOTHING`이 PK에만 적용되어, 새 ID(1021+, 1049+, ...)로 중복 카테고리가 계속 생성됨. 상품은 원본 ID(1~1020)에만 연결되어 중복 카테고리에는 상품이 없었음.

**해결:** `sql.init.mode: always` → `never`로 변경. 기존 중복 데이터 수동 삭제 SQL 제공.

### Issue #9: 검색 페이지네이션 URL 오류

**현상:** 검색 결과 페이지에서 페이지 버튼 클릭 시 0건 표시

**원인:** 페이지네이션 fragment가 항상 `?page=`로 URL을 조립하지만, 검색의 baseUrl이 이미 `/search?q=노트북`으로 `?`를 포함. 결과 URL: `/search?q=노트북?page=0` → 브라우저가 `q=노트북?page=0`으로 해석하여 "노트북?page=0"이라는 키워드로 검색함.

**해결:** pagination fragment에 `baseUrl.contains('?')` 체크를 추가하여 `?` 또는 `&`를 동적으로 선택:
```html
<th:block th:with="separator=${baseUrl.contains('?') ? '&amp;' : '?'}">
    th:href="${baseUrl + separator + 'page=' + i}"
</th:block>
```

### 발견 패턴: "스키마 완성, 로직 미연결"

이슈 #4, #5, #6, #7에서 공통적으로 나타난 패턴이다. 데이터베이스 스키마와 엔티티 구조는 잘 설계되어 있고, 유틸리티 메서드도 구현되어 있지만, 실제 비즈니스 로직에서 이들을 연결하여 호출하는 코드가 누락되어 있었다. 이는 스키마 설계를 먼저 하고 애플리케이션 코드를 구현하는 Top-Down 방식에서 자주 발생하는 패턴이다.

---

## 6. 성능 최적화

### 6.1 Application-Level Cache: Spring Cache + Caffeine

**문제:** 메인 페이지 로드 시 매 요청마다 9개의 DB 쿼리 실행 (약 3초 소요)

```
요청당 쿼리:
  1. categoryService.getTopLevelCategories()     → 1 쿼리
  2. productService.getBestSellers(PageRequest)   → 2 쿼리 (SELECT + COUNT)
  3. productService.getNewArrivals(PageRequest)   → 2 쿼리 (SELECT + COUNT)
  4. productService.getDeals(PageRequest)          → 2 쿼리 (SELECT + COUNT)
  5. searchService.getPopularKeywords()            → 2 쿼리 (GROUP BY + 7일 검색 로그)
  합계: 9 쿼리/요청
```

**해결:** Caffeine 기반 In-Memory Cache를 5개 캐시에 적용 (TTL 5분, 최대 100 엔트리)

```java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
            "bestSellers", "newArrivals", "deals", "topCategories", "popularKeywords"
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100));
        return cacheManager;
    }
}
```

5개 서비스 메서드에 `@Cacheable` 적용:

| 캐시 | 서비스 메서드 | 키 |
|------|-------------|-----|
| `bestSellers` | ProductService.getBestSellers() | `'home'` |
| `newArrivals` | ProductService.getNewArrivals() | `'home'` |
| `deals` | ProductService.getDeals() | `'home'` |
| `topCategories` | CategoryService.getTopLevelCategories() | `'all'` |
| `popularKeywords` | SearchService.getPopularKeywords() | `'top10'` |

**효과:**

| 상태 | 쿼리 수 | 응답 시간 |
|------|---------|----------|
| 캐시 미적중 (최초/5분 후) | 9 쿼리 | ~3초 (인덱스 최적화 전) |
| 캐시 적중 (5분 이내) | 0 쿼리 | 즉시 |

### 6.2 Database-Level: 인덱스 최적화

**문제:** 캐시 미적중 시에도 여전히 느린 쿼리들

기존 인덱스의 문제점:
- `idx_product_sales(sales_count DESC)`: `is_active=true` 필터가 인덱스에 없어 Full Scan 후 필터
- `idx_product_created(created_at DESC)`: 동일 문제
- `findDeals` 쿼리의 `ORDER BY (original_price - price) DESC`: 계산식 정렬로 인한 Full Scan + Full Sort

**해결 — 5개 인덱스 교체:**

| 기존 | 변경 후 | 개선 효과 |
|------|--------|----------|
| `(sales_count DESC)` | `(is_active, sales_count DESC)` | WHERE → ORDER BY 순서 일치 |
| `(created_at DESC)` | `(is_active, created_at DESC)` | Index Scan으로 전환 |
| `(category_id, is_active)` | `(category_id, is_active, sales_count DESC)` | 정렬까지 인덱스로 처리 |
| (없음) | `(is_active, rating_avg DESC, review_count DESC)` | 평점순 조회 지원 |
| (없음) | `((original_price - price) DESC) WHERE ...` | **Partial Expression Index** |

**Partial Expression Index 상세 (findDeals):**

```sql
CREATE INDEX idx_product_deals ON products ((original_price - price) DESC)
    WHERE is_active = true AND original_price IS NOT NULL AND original_price > price;
```

기존 JPQL에서는 Hibernate가 생성하는 SQL이 인덱스 표현식과 정확히 일치하지 않아 인덱스를 사용하지 못했다. 이를 Native Query로 변환하여 인덱스 표현식과 WHERE 절이 정확히 일치하도록 했다:

```java
@Query(value = "SELECT p.* FROM products p " +
       "WHERE p.is_active = true AND p.original_price IS NOT NULL AND p.original_price > price " +
       "ORDER BY (p.original_price - p.price) DESC",
       nativeQuery = true)
Page<Product> findDeals(Pageable pageable);
```

**최적화 전후 비교 (findDeals, 100만 상품 기준):**

| | Before | After |
|---|--------|-------|
| 스캔 방식 | Sequential Scan (100만 행) | Index Scan (10만 행) |
| 정렬 | 메모리 내 Full Sort | 사전 정렬된 인덱스 |
| 인덱스 크기 | 전체 100만 행 | 할인 상품 10만 행만 |
| 계산 | 매 행 `original_price - price` 계산 | 사전 계산된 값 |

### 6.3 최적화 결합 효과

```
[사용자 요청: GET /]
       │
       ├─ 캐시 적중 (5분 이내): → 0 쿼리, 즉시 응답
       │
       └─ 캐시 미적중 (최초/5분 후):
              → 9 쿼리 실행
              → 각 쿼리가 최적화된 인덱스 사용
              → 결과를 Caffeine에 캐싱
              → 이후 5분간 0 쿼리
```

---

## 7. 전체 수정 파일 목록

프로젝트 진행 기간 동안 수정 및 생성한 파일의 전체 목록이다.

### 7.1 신규 생성 파일

| 파일 | 설명 |
|------|------|
| `global/config/CacheConfig.java` | Caffeine 캐시 매니저 설정 (5개 캐시, 5분 TTL) |
| `user/scheduler/TierScheduler.java` | 연간 등급 재산정 스케줄러 (매년 1/1 실행) |

### 7.2 수정 파일

| 파일 | 수정 내용 |
|------|----------|
| `ShopApplication.java` | `@EnableScheduling` 추가 |
| `order/service/OrderService.java` | 등급 혜택 적용, 쿠폰 연동, 취소 롤백, 등급 재산정 |
| `order/controller/OrderController.java` | 체크아웃에 쿠폰 목록 + 등급 정보 전달 |
| `order/repository/OrderRepository.java` | 연간 주문금액 집계 쿼리 추가 |
| `user/entity/User.java` | `addTotalSpent`/`addPoints` floor at zero, `setTotalSpent()` 추가 |
| `coupon/entity/UserCoupon.java` | `cancelUse()` 메서드 추가 |
| `coupon/repository/UserCouponRepository.java` | `findByOrderId()`, `findCouponIdsByUserId()` 추가 |
| `coupon/service/CouponService.java` | `issueCouponById()`, `getUserIssuedCouponIds()`, `issueToUser()` 추출 |
| `coupon/controller/CouponController.java` | ID 기반 발급 엔드포인트, 발급 상태 전달 |
| `review/controller/ReviewController.java` | `POST /{reviewId}/helpful` 엔드포인트 추가 |
| `review/service/ReviewService.java` | `markHelpful()` 메서드 추가 |
| `product/service/ProductService.java` | `@Cacheable` 3개 메서드 적용 |
| `category/service/CategoryService.java` | `@Cacheable` 적용 |
| `search/service/SearchService.java` | `@Cacheable` 적용 |
| `product/repository/ProductRepository.java` | `findDeals` Native Query 변환 |
| `build.gradle` | spring-boot-starter-cache, caffeine 의존성 추가 |
| `application.yml` | `sql.init.mode: always` → `never` |
| `schema.sql` | 5개 인덱스 최적화 (복합/부분 표현식 인덱스) |

### 7.3 템플릿 수정

| 파일 | 수정 내용 |
|------|----------|
| `order/checkout.html` | 등급 배지 + 혜택 정보, 쿠폰 선택, JS 실시간 계산 |
| `coupon/index.html` | 페이지네이션, 발급 버튼/"발급완료" 상태 표시 |
| `product/detail.html` | 리뷰 "도움이 돼요" 버튼 + 카운트 |
| `fragments/product-card.html` | 페이지네이션 URL `?`/`&` 동적 구분 |

---

## 8. 프로젝트 구조 요약

```
shopping-mall/
├── build.gradle
├── src/main/
│   ├── java/com/shop/
│   │   ├── ShopApplication.java
│   │   ├── domain/
│   │   │   ├── cart/          (4 files)
│   │   │   ├── category/      (4 files)
│   │   │   ├── coupon/        (5 files)
│   │   │   ├── inventory/     (3 files)
│   │   │   ├── order/         (6 files)
│   │   │   ├── product/       (7 files)
│   │   │   ├── review/        (5 files)
│   │   │   ├── search/        (4 files)
│   │   │   ├── user/          (10 files, incl. scheduler)
│   │   │   └── wishlist/      (4 files)
│   │   └── global/
│   │       ├── common/        (1 file)
│   │       ├── config/        (3 files)
│   │       ├── exception/     (4 files)
│   │       └── security/      (3 files)
│   └── resources/
│       ├── application.yml
│       ├── schema.sql          (15 tables, 50+ indexes)
│       ├── data.sql            (초기 데이터)
│       └── templates/          (22 HTML files)
│
Total: 67 Java files, 22 HTML templates, 15 DB tables
```

---

## 9. Key Takeaways

**아키텍처 관점:**
- Package-by-Feature 패턴으로 도메인 간 응집도를 높이고 결합도를 낮췄다.
- `@Transactional` 단일 트랜잭션 내에서 7개 테이블 원자적 변경이라는 복잡한 비즈니스 로직을 안전하게 처리했다.
- PRG(Post-Redirect-Get) 패턴으로 중복 주문 방지를 구현했다.

**성능 관점:**
- 캐시(Application-Level)와 인덱스(DB-Level)를 결합한 다층 최적화 전략이 가장 효과적이다.
- Partial Expression Index는 계산식 정렬이 필요한 쿼리에 극적인 성능 향상을 제공한다.
- Native Query는 PostgreSQL 전용 인덱스(Expression Index)를 활용할 때 반드시 필요하다.

**설계 관점:**
- 스키마를 먼저 설계하는 Top-Down 접근에서는 "데이터 구조 완성 ↔ 비즈니스 로직 미연결" 패턴에 주의해야 한다.
- 주문 취소와 같은 역방향 프로세스는 정방향(주문 생성)의 모든 사이드 이펙트를 빠짐없이 역전해야 한다.
- DB 초기화 설정(`sql.init.mode`)은 개발 환경에서도 `never` 또는 `embedded`를 권장한다.

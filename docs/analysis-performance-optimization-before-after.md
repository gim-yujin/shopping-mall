# 성능 최적화 Before/After 비교 분석

> **범위**: Phase 25에서 수행한 4가지 성능 최적화의 변경 내용과 Before/After 비교를 정리한다.
> 각 항목별로 문제 원인, 해결 방식, 성능 지표 변화를 기술한다.

---

## 목차

1. [최적화 요약표](#1-최적화-요약표)
2. [Optimization 1: MyPageController — 순차 호출 + 레거시 쿼리 → 병렬 호출 + CQRS](#2-optimization-1-mypagecontroller)
3. [Optimization 2: CategoryService — 재귀 N+1 → WITH RECURSIVE CTE](#3-optimization-2-categoryservice)
4. [Optimization 3: ReviewService — 트랜잭션 범위 축소](#4-optimization-3-reviewservice)
5. [Optimization 4: AdminController — 순차 호출 → 병렬 호출](#5-optimization-4-admincontroller)
6. [변경 파일 목록](#6-변경-파일-목록)

---

## 1. 최적화 요약표

| 최적화 | 지표 | Before | After |
|--------|------|--------|-------|
| MyPageController | 실행 방식 | 3 순차 호출 | 3 병렬 호출 (StructuredTaskScope) |
| MyPageController | 응답 지연 | sum(T1+T2+T3) | max(T1,T2,T3) |
| MyPageController | 주문 쿼리 수 | 2 (2-query 엔티티 패턴) | 1 (v_order_list 뷰 단일 쿼리) |
| MyPageController | 쿠폰 장애 영향 | 전체 페이지 장애 | 빈 목록 폴백, 페이지 정상 |
| CategoryService | 쿼리 수 (cold) | N (트리 노드 수) | 1 (CTE 단일 쿼리) |
| CategoryService | 무한 재귀 방지 | Java `visited` Set | SQL `depth < 10` |
| ReviewService | 메인 TX 범위 | save + rating쿼리2 + update1 = 4ops | save = 1op |
| ReviewService | 캐시 일관성 | 커밋 전 evict (stale 경합 위험) | 커밋 후 evict (안전) |
| ReviewService | 후처리 실패 영향 | 리뷰 저장까지 롤백 | 리뷰 저장 유지, 후처리만 로그 |
| AdminController | 실행 방식 | 4 순차 호출 | 4 병렬 호출 (StructuredTaskScope) |
| AdminController | 응답 지연 | sum(T1+T2+T3+T4) | max(T1,T2,T3,T4) |

---

## 2. Optimization 1: MyPageController

### 2-1. 문제

`MyPageController.myPage()`가 3개 서비스를 순차적으로 호출했다:

```java
// Before: 순차 호출, 2-query 엔티티 패턴
User user = userService.findById(userId);                          // T1
Page<Order> orders = orderService.getOrdersByUser(userId, pageable); // T2 (2-query)
List<UserCoupon> coupons = couponService.getAvailableCoupons(userId); // T3
```

**문제점:**
- 응답 지연 = T1 + T2 + T3 (순차 합산)
- `getOrdersByUser()`는 2-query 패턴 — Page 쿼리 + `findWithItemsByOrderIds()` JOIN FETCH = 총 2쿼리
- 쿠폰 서비스 장애 시 전체 마이페이지 로딩 실패
- Thymeleaf에서 Order 엔티티의 Lazy 컬렉션에 직접 접근 (`order.items[0].productName`)

### 2-2. 해결

`MyPagePreviewService`를 도입하여 `StructuredTaskScope.ShutdownOnFailure`로 3개 호출을 병렬 실행한다.

```java
// After: 병렬 호출 + CQRS flat 쿼리 + 쿠폰 폴백
MyPagePreview preview = myPagePreviewService.getPreview(userId);
```

**변경 사항:**
- `getOrdersByUser()` → `getOrdersByUserFlat()`: v_order_list 뷰 단일 쿼리로 전환 (2-query → 1-query)
- 쿠폰 서비스: `executeWithFallback()`으로 장애 시 빈 목록 폴백
- 템플릿: `order.items[0].productName` → `order.firstProductName`, 상태 배지를 Map 룩업(`orderStatusLabels`, `orderStatusBadgeClasses`)으로 변경
- `ResilientCallExecutor` 래핑: Retry + CircuitBreaker + TimeLimiter 적용

### 2-3. Before/After 비교

| 지표 | Before | After |
|------|--------|-------|
| 실행 방식 | 순차 (T1 → T2 → T3) | 병렬 (max(T1,T2,T3)) |
| 주문 쿼리 | 2회 (Page + JOIN FETCH) | 1회 (v_order_list 뷰) |
| JPA 프록시 | Order/OrderItem 엔티티 로드 | OrderListReadModel record (프록시 없음) |
| 쿠폰 장애 | 전체 페이지 500 에러 | 빈 목록 폴백, 페이지 정상 표시 |
| SecurityContext 전파 | 해당 없음 (메인 스레드) | `propagatingThreadFactory()` 사용 |

---

## 3. Optimization 2: CategoryService

### 3-1. 문제

`getAllDescendantIds()`가 재귀적으로 `findByParentId()`를 호출하여 트리 노드마다 쿼리가 발생했다:

```java
// Before: 재귀 Java 메서드 — 노드마다 SELECT
public List<Integer> getAllDescendantIds(Integer categoryId) {
    List<Integer> ids = new ArrayList<>();
    Set<Integer> visited = new HashSet<>();
    ids.add(categoryId);
    visited.add(categoryId);
    collectChildIds(categoryId, ids, visited);  // N+1 쿼리!
    return ids;
}

private void collectChildIds(Integer parentId, List<Integer> ids, Set<Integer> visited) {
    List<Category> children = categoryRepository.findByParentId(parentId); // SELECT per node
    for (Category child : children) {
        if (visited.add(child.getCategoryId())) {
            ids.add(child.getCategoryId());
            collectChildIds(child.getCategoryId(), ids, visited); // 재귀
        }
    }
}
```

**문제점:**
- 트리 깊이 D, 총 노드 N일 때 쿼리 수 = N (루트 제외 모든 노드에 대해 1회)
- 캐시 미스 시 카테고리 트리가 클수록 성능 저하
- 순환 감지를 Java `visited` Set으로 처리

### 3-2. 해결

PostgreSQL `WITH RECURSIVE` CTE로 단일 쿼리 대체:

```sql
WITH RECURSIVE descendants AS (
    SELECT category_id, 0 AS depth
    FROM categories WHERE category_id = :categoryId
    UNION ALL
    SELECT c.category_id, d.depth + 1
    FROM categories c
    INNER JOIN descendants d ON c.parent_category_id = d.category_id
    WHERE c.is_active = true AND d.depth < 10
)
SELECT category_id FROM descendants
```

```java
// After: 단일 CTE 쿼리
public List<Integer> getAllDescendantIds(Integer categoryId) {
    return categoryRepository.findAllDescendantIds(categoryId);
}
```

### 3-3. Before/After 비교

| 지표 | Before | After |
|------|--------|-------|
| DB 쿼리 수 (cold) | N (트리 노드 수) | 1 |
| DB 왕복 | 노드마다 1회 | 1회 |
| 무한 재귀 방지 | Java `visited` Set | SQL `depth < 10` |
| Java 코드 | `collectChildIds()` 재귀 메서드 필요 | 리포지토리 단일 호출 |
| 캐시 히트 시 | 동일 (0 쿼리) | 동일 (0 쿼리) |
| `is_active` 필터 | JPQL `findByParentId()`에서 적용 | CTE JOIN 조건에서 적용 |

---

## 4. Optimization 3: ReviewService

### 4-1. 문제

`createReview()`, `deleteReview()`, `updateReview()`에서 리뷰 저장과 후처리가 동일 `@Transactional` 범위에서 실행되었다:

```java
// Before: 메인 트랜잭션 내 4개 작업
@Transactional
public Review createReview(Long userId, ReviewCreateRequest request) {
    ...
    Review saved = reviewRepository.save(review);        // (1) 리뷰 INSERT
    updateProductRating(request.productId());              // (2) rating AVG 쿼리 + COUNT 쿼리
                                                           // (3) Product UPDATE
    productService.evictProductDetailCache(...);            // (4) 캐시 evict
    bumpProductReviewVersion(...);                          // (5) 캐시 버전 bump
    return saved;
}
```

**문제점:**
- 메인 TX가 save + 2 rating 쿼리 + 1 update = 4 DB 작업을 포함하여 길어짐
- 커밋 전에 캐시를 evict하므로, evict 후 ~ 커밋 전 사이에 다른 요청이 stale 데이터를 캐시에 재적재하는 경합 가능
- rating 계산 쿼리 실패 시 리뷰 저장까지 롤백

### 4-2. 해결

`ReviewRatingChangedEvent` 도메인 이벤트 + `ReviewPostProcessingListener`로 후처리를 분리:

```java
// After: 메인 TX에서 이벤트 발행만
@Transactional
public Review createReview(Long userId, ReviewCreateRequest request) {
    ...
    Review saved = reviewRepository.save(review);
    eventPublisher.publishEvent(new ReviewRatingChangedEvent(request.productId()));
    return saved;
}

// 리스너: 커밋 후 별도 TX에서 후처리
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void handleReviewRatingChanged(ReviewRatingChangedEvent event) {
    updateProductRating(event.productId());
    productService.evictProductDetailCache(event.productId());
    bumpProductReviewVersion(event.productId());
}
```

### 4-3. Before/After 비교

| 지표 | Before | After |
|------|--------|-------|
| 메인 TX 작업 수 | 4 (save + rating쿼리2 + update1) | 1 (save만) |
| 메인 TX 보유 시간 | 길다 (rating 계산 포함) | 짧다 (save만) |
| 캐시 evict 타이밍 | 커밋 전 (stale 경합 위험) | 커밋 후 (AFTER_COMMIT, 안전) |
| 후처리 실패 시 | 리뷰 저장까지 롤백 | 리뷰 저장 유지, 후처리만 로그 |
| 후처리 격리 | 없음 (동일 TX) | REQUIRES_NEW (별도 TX) |
| createReview/deleteReview/updateReview | 각각 후처리 코드 중복 | 단일 이벤트 핸들러로 통합 |
| ReviewService 의존성 | ProductRepository, ProductService 필요 | 제거됨 (이벤트 발행만) |

---

## 5. Optimization 4: AdminController

### 5-1. 문제

`AdminController.dashboard()`가 4개 서비스를 순차적으로 호출했다:

```java
// Before: 순차 호출
model.addAttribute("products", productService.findAllForAdmin(...));       // T1
model.addAttribute("recentOrders", orderService.getAllOrdersFlat(...));     // T2
model.addAttribute("couponStats", couponService.getCouponStats());         // T3
model.addAttribute("pendingReturnCount", orderService.getPendingReturnCount()); // T4
```

**문제점:**
- 응답 지연 = T1 + T2 + T3 + T4 (순차 합산)
- 4개 호출이 완전히 독립적임에도 직렬 실행

### 5-2. 해결

`AdminDashboardPreviewService`를 도입하여 `StructuredTaskScope.ShutdownOnFailure`로 4개 호출을 병렬 실행한다.

```java
// After: 병렬 호출
AdminDashboardPreview preview = dashboardPreviewService.getPreview();
model.addAttribute("products", preview.products());
model.addAttribute("recentOrders", preview.recentOrders());
model.addAttribute("couponStats", preview.couponStats());
model.addAttribute("pendingReturnCount", preview.pendingReturnCount());
```

**변경 사항:**
- 4개 호출 모두 관리자 대시보드 필수 데이터 → 폴백 없이 `ShutdownOnFailure` 사용
- 하나라도 실패 시 나머지 작업 자동 취소 후 예외 전파
- `ResilientCallExecutor` 래핑: Retry + CircuitBreaker + TimeLimiter 적용

### 5-3. Before/After 비교

| 지표 | Before | After |
|------|--------|-------|
| 실행 방식 | 순차 (T1 → T2 → T3 → T4) | 병렬 (max(T1,T2,T3,T4)) |
| 장애 전파 | 1개 실패 시 나머지는 이미 실행됨 | ShutdownOnFailure로 즉시 취소 |
| Resilience4j | 미적용 | Retry + CircuitBreaker + TimeLimiter |
| SecurityContext 전파 | 해당 없음 (메인 스레드) | `propagatingThreadFactory()` 사용 |

---

## 6. 변경 파일 목록

### 신규 파일

| 파일 | 설명 |
|------|------|
| `domain/user/dto/MyPagePreview.java` | 마이페이지 프리뷰 데이터 record |
| `domain/user/service/MyPagePreviewService.java` | 마이페이지 3개 서비스 병렬 호출 |
| `domain/product/dto/AdminDashboardPreview.java` | 관리자 대시보드 프리뷰 데이터 record |
| `domain/product/service/AdminDashboardPreviewService.java` | 관리자 대시보드 4개 서비스 병렬 호출 |
| `global/event/ReviewRatingChangedEvent.java` | 리뷰 변경 도메인 이벤트 record |
| `domain/review/service/ReviewPostProcessingListener.java` | 리뷰 후처리 이벤트 리스너 |
| `test/.../ReviewPostProcessingListenerUnitTest.java` | 리스너 단위 테스트 |

### 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `domain/category/repository/CategoryRepository.java` | `findAllDescendantIds()` CTE 쿼리 추가 |
| `domain/category/service/CategoryService.java` | `getAllDescendantIds()` → CTE 위임, `collectChildIds()` 제거 |
| `domain/review/service/ReviewService.java` | 후처리 제거, `ApplicationEventPublisher` 이벤트 발행으로 교체, `ProductRepository`/`ProductService` 의존성 제거 |
| `domain/user/controller/MyPageController.java` | `MyPagePreviewService` 주입, `myPage()` 병렬 호출 전환 |
| `domain/product/controller/AdminController.java` | `AdminDashboardPreviewService` 주입, `dashboard()` 병렬 호출 전환 |
| `templates/mypage/index.html` | OrderListReadModel 필드 접근 + 상태 Map 룩업으로 변경 |
| `test/.../CategoryServiceUnitTest.java` | CTE 위임 검증으로 변경 |
| `test/.../ReviewServiceUnitTest.java` | 이벤트 발행 검증으로 변경 |
| `test/.../ReviewServiceBranchTest.java` | 이벤트 발행 검증으로 변경 |
| `test/.../MyPageControllerUnitTest.java` | `MyPagePreviewService` mock 추가 |
| `test/.../UserControllerSupplementaryUnitTest.java` | `MyPagePreviewService` mock 추가 |
| `test/.../AdminControllerUnitTest.java` | `AdminDashboardPreviewService` mock 추가 |
| `test/.../AdminControllerBranchCoverageTest.java` | `AdminDashboardPreviewService` mock 추가 |
| `test/.../AdminControllerReturnManagementTest.java` | `AdminDashboardPreviewService` mock 추가 |

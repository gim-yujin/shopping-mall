# 쿼리 최적화 — N+1 해결 전략 종합 정리

> **범위**: 이 문서는 프로젝트에서 N+1 문제를 해결하기 위해 적용한 모든 최적화 기법을 정리한다.
> 기법별 원리, 적용 위치, 해결한 문제를 함께 기록하여 유지보수 시 참고할 수 있도록 한다.

---

## 목차

1. [기법 요약](#1-기법-요약)
2. [JOIN FETCH](#2-join-fetch)
3. [2-쿼리 패턴](#3-2-쿼리-패턴)
4. [DB 뷰 + 플랫 프로젝션 (CQRS 읽기 모델)](#4-db-뷰--플랫-프로젝션-cqrs-읽기-모델)
5. [@EntityGraph](#5-entitygraph)
6. [@BatchSize + 명시적 초기화](#6-batchsize--명시적-초기화)
7. [서비스 레벨 일괄 조회](#7-서비스-레벨-일괄-조회)
8. [캐시 DTO 물질화](#8-캐시-dto-물질화)
9. [적용 타임라인](#9-적용-타임라인)
10. [통계](#10-통계)

---

## 1. 기법 요약

| 기법 | 원리 | 적용 조건 | 쿼리 수 |
|---|---|---|---|
| JOIN FETCH | JPQL에서 연관 엔티티를 한 쿼리로 즉시 로딩 | 단건 조회 또는 `List` 반환 | 1 |
| 2-쿼리 패턴 | 페이징 쿼리 후 ID 목록으로 컬렉션을 별도 JOIN FETCH | `Page` + 컬렉션(`@OneToMany`) | 2 |
| DB 뷰 + 플랫 프로젝션 | 서브쿼리로 집계값을 뷰에 내장, 네이티브 SQL로 DTO 직접 매핑 | 목록 페이지(읽기 전용) | 1 |
| @EntityGraph | JPA 표준 fetch plan 지정 | 스프링 Data 기본 메서드 오버라이드 | 1 |
| @BatchSize + 초기화 | Lazy 컬렉션 접근 시 IN 절로 일괄 로딩 | `Page` + 컬렉션(JOIN FETCH 불가) | 1+1 |
| 서비스 레벨 일괄 조회 | 대상 ID를 수집 후 `findAllById(ids)`로 한 번에 조회 | 연관 엔티티가 다른 도메인에 속할 때 | 1+1 |
| 캐시 DTO 물질화 | 트랜잭션 내에서 Lazy 프록시를 불변 record로 변환 후 캐시 저장 | 캐시 대상 조회 | 1 (캐시 miss 시) |

---

## 2. JOIN FETCH

JPQL `JOIN FETCH`로 연관 엔티티를 한 번의 쿼리에 포함시킨다.
단건 조회이거나 `List` 반환(페이징 없음)일 때 가장 단순하고 효과적이다.

### 2-1. Product 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findByCategoryId()` | `ProductRepository.java:26` | `p.category` | 카테고리별 상품 목록에서 카테고리명 접근 시 N회 추가 쿼리 |
| `findByCategoryIds()` | `ProductRepository.java:32` | `p.category` | 다중 카테고리 IN 절 쿼리에서 동일 문제 |
| `findBestSellers()` | `ProductRepository.java:51` | `p.category` | 메인 페이지 베스트셀러 카테고리 N+1 (트래픽 최다 페이지) |
| `findNewArrivals()` | `ProductRepository.java:57` | `p.category` | 신상품 목록 카테고리 N+1 |
| `findByIdWithCategory()` | `ProductRepository.java:107` | `p.category`, `p.images` | 상품 상세에서 카테고리 + 썸네일 이미지 조회 |

`findByIdWithCategory()`는 Phase 20에서 `LEFT JOIN FETCH p.images`를 추가하여 `getThumbnailUrl()`이 placeholder 대신 실제 썸네일을 반환하도록 개선했다.

### 2-2. Order 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findWithItemsByOrderIds()` | `OrderRepository.java:39` | `o.items` | 2-쿼리 패턴의 2차 쿼리 (아래 §3 참고) |
| `findByIdAndUserId()` | `OrderRepository.java:44` | `o.items` | 주문 상세 조회에서 아이템 Lazy 로딩 |

### 2-3. OrderItem 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findByIdWithOrder()` | `OrderItemRepository.java:30` | `oi.order` | 리뷰 작성 검증 시 Order.userId/status 접근 |
| `findDeliveredItemsForReviewExcludingReviewed()` | `OrderItemRepository.java:33` | `oi.order` | 리뷰 가능 주문 항목 조회 시 Order 접근 |
| `findByIdAndOrderIdAndUserIdWithLock()` | `OrderItemRepository.java:49` | `oi.order` | 부분 취소/반품 시 Order 접근 (비관적 잠금 포함) |
| `findByStatus()` | `OrderItemRepository.java:77` | `oi.order` | 반품 대기 목록에서 주문 번호/사용자 정보 표시 |

### 2-4. Cart 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findByUserIdWithProduct()` | `CartRepository.java:15` | `c.product`, `p.images` | 장바구니 목록에서 상품명/가격/썸네일 접근 |
| `findByUserIdAndCartIdIn()` | `CartRepository.java:24` | `c.product`, `p.images` | 선택 주문 시 장바구니 항목의 상품 정보 접근 |

`DISTINCT`를 추가하여 images 컬렉션 JOIN으로 인한 Cart 중복 행을 제거한다.

### 2-5. Coupon 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findAvailableCoupons()` | `UserCouponRepository.java:21` | `uc.coupon` | 사용 가능 쿠폰 목록에서 쿠폰 정보 접근 |
| `findByUserId()` | `UserCouponRepository.java:35` | `uc.coupon` | 마이페이지 쿠폰 목록 (Page + countQuery 분리) |
| `findByIdWithLock()` | `UserCouponRepository.java:40` | `uc.coupon` | 쿠폰 사용 시 쿠폰 유효성 확인 (비관적 잠금 포함) |

### 2-6. User 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findByIdWithTier()` | `UserRepository.java:24` | `u.tier` | 사용자 조회 시 등급 정보 접근 |
| `findByIdWithLockAndTier()` | `UserRepository.java:28` | `u.tier` | 등급 변경 시 잠금 + 등급 조회 |
| `findAllByIdInWithLockAndTierOrderByUserId()` | `UserRepository.java:32` | `u.tier` | 배치 등급 재산정 시 다건 잠금 + 등급 조회 |

### 2-7. Wishlist 도메인

| 메서드 | 파일:라인 | FETCH 대상 | 해결한 N+1 |
|---|---|---|---|
| `findByUserIdWithProduct()` | `WishlistRepository.java:14` | `w.product` | 위시리스트 목록에서 상품 정보 접근 |

> **주의**: 위시리스트는 `Page` 반환이므로 `p.images` JOIN FETCH를 추가하면 Hibernate가 전체 결과를 메모리에 로드한다.
> 이미지는 `@BatchSize` + `Hibernate.initialize()`로 별도 처리한다 (§6 참고).

---

## 3. 2-쿼리 패턴

`Page<Entity>` + 컬렉션(`@OneToMany`) 조합에서 JOIN FETCH를 사용하면
Hibernate가 페이징을 애플리케이션 메모리에서 수행한다 (HHH000104 경고).
데이터가 많을수록 OOM 위험이 커진다.

**해결**: 페이징 쿼리와 컬렉션 로딩을 분리한다.

```
1차 쿼리: Page<Order> — 페이징 (items 미로드)
2차 쿼리: findWithItemsByOrderIds(ids) — IN절 + JOIN FETCH items
```

2차 쿼리로 로드된 Order는 영속성 컨텍스트에서 1차 쿼리의 Order와 동일 식별자로 병합된다.
이후 `order.getItems()` 호출 시 추가 쿼리가 발생하지 않는다.

### 적용 위치

| 서비스 메서드 | 파일:라인 | 1차 쿼리 | 2차 쿼리 |
|---|---|---|---|
| `getOrdersByUser()` | `OrderQueryService.java:63` | `findByUserId()` | `findWithItemsByOrderIds()` |
| `getAllOrders()` | `OrderQueryService.java:74` | `findAllByOrderByOrderDateDesc()` | `findWithItemsByOrderIds()` |
| `getOrdersByStatus()` | `OrderQueryService.java:80` | `findByStatus()` | `findWithItemsByOrderIds()` |

> **현재 상태**: Phase 18에서 CQRS 플랫 프로젝션(§4)이 도입되면서, 이 메서드들은 Thymeleaf SSR 경로의 폴백으로 남아 있다.
> 신규 API 경로는 `*Flat()` 메서드를 사용한다.

---

## 4. DB 뷰 + 플랫 프로젝션 (CQRS 읽기 모델)

Phase 18에서 도입한 CQRS 읽기 모델 전략이다.
JPA 엔티티 대신 DB 뷰의 네이티브 SQL 결과를 불변 record에 직접 매핑한다.

### 이점

- 필요한 컬럼만 SELECT → 네트워크/메모리 절감
- 서브쿼리로 집계값(썸네일 URL, 아이템 수)을 한 번에 계산 → N+1 원천 차단
- JPA 영속성 컨텍스트 미사용 → 스냅샷 보관/dirty checking GC 부담 제거
- 불변 record → 캐시 데이터 오염 원천 차단

### 4-1. v_product_list 뷰

```sql
-- schema.sql:714
CREATE OR REPLACE VIEW v_product_list AS
SELECT
    p.product_id, p.product_name, p.price, p.original_price,
    p.rating_avg, p.review_count, p.sales_count,
    c.category_id, c.category_name, p.created_at,
    COALESCE(
        (SELECT pi.image_url FROM product_images pi
         WHERE pi.product_id = p.product_id AND pi.is_thumbnail = true
         LIMIT 1),
        '/images/product-placeholder.svg'
    ) AS thumbnail_url,
    p.is_active
FROM products p
JOIN categories c ON c.category_id = p.category_id;
```

**해결한 N+1**: `Product.getThumbnailUrl()`이 Lazy `images` 컬렉션을 초기화하여 상품 N개당 N회 추가 쿼리 발생 → 서브쿼리로 인라인 처리.

| 쿼리 메서드 | 파일:라인 | 읽기 모델 |
|---|---|---|
| `findBestSellersFlat()` | `ProductRepository.java:130` | `ProductListReadModel` |
| `findNewArrivalsFlat()` | `ProductRepository.java:140` | `ProductListReadModel` |
| `findDealsFlat()` | `ProductRepository.java:150` | `ProductListReadModel` |
| `findActiveProductsFlatContent()` + `countActiveProducts()` | `ProductRepository.java:174,178` | `ProductListReadModel` |
| `findByCategoryIdsFlatContent()` + `countActiveByCategoryIds()` | `ProductRepository.java:185,190` | `ProductListReadModel` |
| `searchByKeywordFlat()` | `ProductRepository.java:182` | `ProductListReadModel` |
| `searchByKeywordLikeFlat()` | `ProductRepository.java:194` | `ProductListReadModel` |

### 4-2. v_order_list 뷰

```sql
-- schema.sql:744
CREATE OR REPLACE VIEW v_order_list AS
SELECT
    o.order_id, o.order_number, o.user_id, o.order_status,
    o.total_amount, o.discount_amount, o.shipping_fee, o.final_amount,
    o.order_date, o.paid_at, o.shipped_at, o.delivered_at, o.cancelled_at,
    (SELECT COUNT(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS item_count,
    (SELECT oi2.product_name FROM order_items oi2
     WHERE oi2.order_id = o.order_id
     ORDER BY oi2.order_item_id LIMIT 1) AS first_product_name
FROM orders o;
```

**해결한 N+1**: 주문 목록에서 아이템 수를 계산하기 위해 전체 `OrderItem` 컬렉션을 로딩하던 2-쿼리 패턴을 서브쿼리 단일 쿼리로 대체.

| 쿼리 메서드 | 파일:라인 | 읽기 모델 |
|---|---|---|
| `findByUserIdFlat()` | `OrderRepository.java:84` | `OrderListReadModel` |
| `findAllOrdersFlat()` | `OrderRepository.java:96` | `OrderListReadModel` |
| `findByStatusFlat()` | `OrderRepository.java:108` | `OrderListReadModel` |

---

## 5. @EntityGraph

JPA 표준 fetch plan을 선언적으로 지정한다. Spring Data JPA의 기본 메서드(`findAll` 등)를
오버라이드할 때 JPQL 없이 fetch 전략을 변경할 수 있다.

| 메서드 | 파일:라인 | attributePaths | 해결한 N+1 |
|---|---|---|---|
| `findAll(Pageable)` | `UserRepository.java:35` | `tier` | 관리자 사용자 목록에서 등급 정보 접근 |
| `findUsersAfterIdWithTier()` | `UserRepository.java:44` | `tier` | 배치 스케줄러 cursor 기반 사용자 청크 조회 시 등급 접근 |

> `findUsersAfterIdWithTier()`는 keyset(cursor) 기반 페이징으로 OFFSET 없이 일정한 O(limit) 성능을 보장한다.
> 100만+ 사용자에서 OFFSET 방식 대비 마지막 페이지의 수십 초 지연을 제거했다.

---

## 6. @BatchSize + 명시적 초기화

`Page` 반환 쿼리에서 컬렉션을 JOIN FETCH하면 Hibernate가 페이징을 인메모리로 수행한다.
이 경우 `@BatchSize`로 Lazy 컬렉션의 일괄 로딩 크기를 지정하고,
트랜잭션 내에서 `Hibernate.initialize()`를 명시적으로 호출하여 초기화한다.

### 적용

```java
// Product.java:89 — 엔티티 컬렉션에 @BatchSize 선언
@OneToMany(mappedBy = "product", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
@OrderBy("imageOrder ASC")
@BatchSize(size = 30)
private List<ProductImage> images = new ArrayList<>();
```

```java
// WishlistService.java:31 — 트랜잭션 내에서 명시적 초기화
public Page<Wishlist> getWishlist(Long userId, Pageable pageable) {
    Page<Wishlist> page = wishlistRepository.findByUserIdWithProduct(userId, pageable);
    page.getContent().forEach(w -> Hibernate.initialize(w.getProduct().getImages()));
    return page;
}
```

**동작 원리**: 첫 번째 `getImages()` 호출 시 Hibernate가 영속성 컨텍스트 내 모든 `Product`의 `images`를
IN 절 하나로 일괄 로딩한다. 페이지 크기가 20이면 쿼리 1회로 최대 20개 상품의 이미지를 가져온다.

### 방어적 체크

```java
// Product.java:183 — OSIV=off 환경에서 LazyInitializationException 방지
public String getThumbnailUrl() {
    if (!Hibernate.isInitialized(images) || images == null) {
        return "/images/product-placeholder.svg";
    }
    return images.stream()
            .filter(ProductImage::getIsThumbnail)
            .findFirst()
            .map(ProductImage::getImageUrl)
            .orElse("/images/product-placeholder.svg");
}
```

이 프로젝트는 `spring.jpa.open-in-view=false`이므로 트랜잭션 바깥에서 Lazy 프록시에 접근하면
`LazyInitializationException`이 발생한다. `Hibernate.isInitialized()` 체크로 미초기화 시
안전하게 placeholder를 반환한다.

---

## 7. 서비스 레벨 일괄 조회

연관 엔티티가 다른 도메인에 속하여 JOIN FETCH를 사용할 수 없을 때,
서비스 레이어에서 대상 ID를 수집한 후 `findAllById(ids)`로 한 번에 조회한다.

### 적용

```java
// OrderQueryService.java:141 — 반품 대기 목록에서 User 정보 일괄 조회
public Page<AdminReturnResponse> getReturnRequests(Pageable pageable) {
    Page<OrderItem> items = orderItemRepository.findByStatus(
            OrderItemStatus.RETURN_REQUESTED, pageable);  // JOIN FETCH oi.order

    // 대상 userId 수집 → IN 쿼리 1회로 User 일괄 조회
    Set<Long> userIds = items.getContent().stream()
            .map(oi -> oi.getOrder().getUserId())
            .collect(Collectors.toSet());
    Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getUserId, Function.identity()));

    return items.map(oi -> { /* userMap에서 User 조회하여 DTO 매핑 */ });
}
```

**해결한 N+1**: 반품 목록에서 각 항목의 사용자명/이메일 표시를 위한 N회 User 조회 → IN 쿼리 1회.

---

## 8. 캐시 DTO 물질화

Caffeine 캐시에 JPA 엔티티를 직접 저장하면 Lazy 프록시가 캐시에 남아
다른 요청에서 `LazyInitializationException`이 발생하거나, 같은 객체 참조를 공유하여
setter 호출 시 캐시 데이터가 오염될 수 있다.

**해결**: 트랜잭션 내에서 모든 Lazy 프록시를 초기화하고 불변 record로 변환한 뒤 캐시에 저장한다.

```java
// CachedProductDetail.java:54 — 캐시 시점에 Lazy 프록시 물질화
public static CachedProductDetail from(Product product) {
    return new CachedProductDetail(
            product.getProductId(),
            product.getProductName(),
            ...
            product.getThumbnailUrl(),   // images 초기화됨 (findByIdWithCategory가 JOIN FETCH)
            product.getCategory() != null ? product.getCategory().getCategoryId() : null,
            product.getCategory() != null ? product.getCategory().getCategoryName() : null,
            ...
    );
}
```

| 캐시 DTO | 소스 엔티티 | 물질화 대상 Lazy 필드 |
|---|---|---|
| `CachedProductDetail` | `Product` | `category`, `images` (→ thumbnailUrl) |
| `ProductListReadModel` | 네이티브 SQL `Object[]` | N/A (Lazy 프록시 자체 없음) |
| `OrderListReadModel` | 네이티브 SQL `Object[]` | N/A (Lazy 프록시 자체 없음) |

---

## 9. 적용 타임라인

| Phase | 적용 내용 |
|---|---|
| Phase 2 | 2-쿼리 패턴 도입: 주문 목록 페이징 + 아이템 일괄 로드 |
| Phase 8 | JOIN FETCH 전면 도입: Product.category, OrderItem.order, UserCoupon.coupon, User.tier |
| Phase 18 | CQRS 읽기 모델 분리: DB 뷰(`v_product_list`, `v_order_list`) + 플랫 프로젝션 + ReadModel record |
| Phase 20 | 이미지 N+1 해결: `findByIdWithCategory`에 images JOIN FETCH, Cart 쿼리에 images JOIN FETCH, `@BatchSize` + Wishlist 명시적 초기화 |

---

## 10. 통계

| 항목 | 수량 |
|---|---|
| JOIN FETCH 쿼리 | 18개 |
| @EntityGraph | 2개 |
| @BatchSize | 1개 (Product.images) |
| DB 뷰 | 2개 (v_product_list, v_order_list) |
| 플랫 프로젝션 쿼리 (뷰 활용) | 10개 |
| 읽기 모델 DTO | 3개 |
| Hibernate.initialize() 호출 | 1개 (WishlistService) |
| Hibernate.isInitialized() 방어 체크 | 1개 (Product.getThumbnailUrl) |
| 서비스 레벨 일괄 조회 | 1개 (OrderQueryService.getReturnRequests) |

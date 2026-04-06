# REST API 사용 설명서

이 문서는 쇼핑몰 프로젝트의 REST API(`/api/**`) 엔드포인트를 정리한 사용 설명서다.
모든 API는 JSON 형식으로 통신하며, SSR(Thymeleaf) 컨트롤러와 동일한 서비스 계층을 공유한다.

---

## 공통 사항

### 기본 URL

```
http://localhost:8080/api/v1
```

### 응답 형식

모든 응답은 `ApiResponse<T>` 래퍼로 감싸져 반환된다.

**성공**
```json
{
  "success": true,
  "data": { ... }
}
```

**실패**
```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "에러 메시지"
  }
}
```

### 페이지네이션 응답

목록 조회 API는 `PageResponse<T>`를 반환한다.

```json
{
  "success": true,
  "data": {
    "content": [ ... ],
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8,
    "first": true,
    "last": false
  }
}
```

### 인증

| 구분 | 설명 |
|---|---|
| `/api/**` 보안 체인 | Stateless, CSRF 비활성 |
| 인증 방식 | 세션 기반 (SecurityContext) |
| 공개 API | `GET /api/v1/products/**`, `GET /api/v1/products/{id}/reviews`, `/api/v1/search/**`, `POST /api/v1/users/signup` |
| 인증 필요 | 그 외 모든 엔드포인트 |
| 관리자 전용 | `/api/v1/admin/**` (ROLE_ADMIN 필요) |

### 공통 에러 코드

| HTTP Status | 코드 | 설명 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 입력값 유효성 검증 실패 (모든 필드 에러를 한 번에 반환) |
| 400 | `{도메인별 코드}` | 비즈니스 로직 오류 (예: `INSUFFICIENT_STOCK`, `ALREADY_ISSUED`) |
| 404 | `{도메인별 코드}` | 리소스를 찾을 수 없음 (예: `ORDER_NOT_FOUND`) |
| 409 | `IDEMPOTENCY_PROCESSING` | 이전 요청이 아직 처리 중 |
| 409 | `IDEMPOTENCY_CONFLICT` | 동일한 요청이 동시에 처리 중 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |

### 멱등성 키 (Idempotency Key)

주문 생성/취소/부분취소, 쿠폰 발급 등 상태 변경 API는 `X-Idempotency-Key` 헤더를 지원한다.

```
X-Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

| 상황 | 동작 |
|---|---|
| 헤더 없음 | 기존 비멱등 동작으로 폴백 (하위 호환) |
| 최초 요청 | 정상 처리 후 결과 저장 |
| 동일 키 + 이전 성공 | 저장된 응답 반환 (재처리 없음) |
| 동일 키 + 이전 처리 중 | `409 Conflict` 반환 |
| 동일 키 + 이전 실패 | 재처리 허용 |

---

## 검색 (Search)

> 인증 불필요 — 모든 엔드포인트가 공개 API

### 상품 검색

```
GET /api/v1/search?q=티셔츠&page=0&size=20
```

FTS(Full-Text Search)로 상품을 검색한다. 첫 페이지 조회 시에만 검색 로그를 기록한다.

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `q` | string | (필수) | 검색 키워드 |
| `page` | int | `0` | 페이지 번호 (0-based) |
| `size` | int | `20` | 페이지 크기 |

**응답 (200 OK)** — `PageResponse<ProductSummaryResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "productId": 1,
        "productName": "반팔 티셔츠",
        "price": 29000,
        "originalPrice": 39000,
        "discountPercent": 25,
        "thumbnailUrl": "/images/product1.jpg",
        "ratingAvg": 4.5,
        "reviewCount": 42,
        "salesCount": 150
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 5,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

### 인기 검색어 조회

```
GET /api/v1/search/popular
```

**응답 (200 OK)** — 상위 10개 인기 검색어

```json
{
  "success": true,
  "data": ["티셔츠", "원피스", "청바지", "운동화", "가방"]
}
```

---

## 상품 (Product)

> 인증 불필요 — 모든 엔드포인트가 공개 API

### 상품 목록 조회

```
GET /api/v1/products?page=0&size=20&sort=best
```

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 페이지 번호 (0-based) |
| `size` | int | `20` | 페이지 크기 |
| `sort` | string | `best` | 정렬 기준: `best`, `newest`, `price_asc`, `price_desc`, `review` |

**응답 (200 OK)** — `PageResponse<ProductSummaryResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "productId": 1,
        "productName": "반팔 티셔츠",
        "price": 29000,
        "originalPrice": 39000,
        "discountPercent": 25,
        "thumbnailUrl": "/images/product1.jpg",
        "ratingAvg": 4.5,
        "reviewCount": 42,
        "salesCount": 150
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8,
    "first": true,
    "last": false
  }
}
```

### 상품 상세 조회

```
GET /api/v1/products/{productId}
```

**응답 (200 OK)** — `ProductDetailResponse`

```json
{
  "success": true,
  "data": {
    "productId": 1,
    "productName": "반팔 티셔츠",
    "description": "시원한 여름용 반팔 티셔츠",
    "price": 29000,
    "originalPrice": 39000,
    "discountPercent": 25,
    "thumbnailUrl": "/images/product1.jpg",
    "categoryName": "상의",
    "categoryId": 2,
    "inStock": true,
    "ratingAvg": 4.5,
    "reviewCount": 42,
    "salesCount": 150,
    "viewCount": 1023,
    "createdAt": "2026-01-15T10:30:00"
  }
}
```

---

## 장바구니 (Cart)

> 모든 엔드포인트 인증 필요

### 장바구니 조회

```
GET /api/v1/cart
```

**응답 (200 OK)** — `CartResponse`

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "cartId": 1,
        "productId": 10,
        "productName": "반팔 티셔츠",
        "price": 29000,
        "thumbnailUrl": "/images/product1.jpg",
        "quantity": 2,
        "subtotal": 58000
      }
    ],
    "totalPrice": 58000,
    "itemCount": 1
  }
}
```

### 장바구니에 상품 추가

```
POST /api/v1/cart
```

이미 존재하는 상품이면 수량이 누적된다.

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `productId` | Long | O | 상품 ID |
| `quantity` | int | O | 수량 (최소 1) |

```json
{
  "productId": 10,
  "quantity": 2
}
```

**응답 (201 Created)** — 추가 후 갱신된 장바구니 전체를 `CartResponse`로 반환

### 장바구니 수량 변경

```
PUT /api/v1/cart/{productId}?quantity=3
```

**Path/Query Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `productId` | Long | 상품 ID |
| `quantity` | int | 변경할 수량 |

**응답 (200 OK)** — 갱신된 장바구니 `CartResponse` 반환

### 장바구니에서 상품 제거

```
DELETE /api/v1/cart/{productId}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

---

## 주문 (Order)

> 모든 엔드포인트 인증 필요

### 주문 생성

```
POST /api/v1/orders
```

**Headers (선택)**

```
X-Idempotency-Key: {UUID}
```

**Request Body**

| 필드 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `shippingAddress` | String | O | — | 배송지 주소 |
| `recipientName` | String | O | — | 수령인 이름 |
| `recipientPhone` | String | O | — | 수령인 연락처 |
| `paymentMethod` | String | — | `"CARD"` | 결제 수단 (`@ValidPaymentMethod`) |
| `userCouponId` | Long | — | `null` | 적용할 사용자 쿠폰 ID |
| `usePoints` | Integer | — | `0` | 사용할 포인트 (0 이상) |
| `cartItemIds` | List\<Long\> | — | `null` | 주문할 장바구니 항목 ID 목록. null/빈 값이면 장바구니 전체 주문 |

```json
{
  "shippingAddress": "서울시 강남구 테헤란로 123",
  "recipientName": "홍길동",
  "recipientPhone": "010-1234-5678",
  "paymentMethod": "CARD",
  "usePoints": 1000,
  "cartItemIds": [1, 2, 3]
}
```

**응답 (201 Created)** — `OrderDetailResponse`

```json
{
  "success": true,
  "data": {
    "orderId": 100,
    "orderNumber": "ORD-20260406-001",
    "orderStatus": "PAID",
    "totalAmount": 87000,
    "discountAmount": 5000,
    "tierDiscountAmount": 2000,
    "couponDiscountAmount": 3000,
    "shippingFee": 0,
    "finalAmount": 82000,
    "earnedPoints": 820,
    "usedPoints": 1000,
    "refundedAmount": 0,
    "refundedPoints": 0,
    "pointsSettled": false,
    "paymentMethod": "CARD",
    "shippingAddress": "서울시 강남구 테헤란로 123",
    "recipientName": "홍길동",
    "recipientPhone": "010-1234-5678",
    "items": [
      {
        "orderItemId": 1,
        "productId": 10,
        "productName": "반팔 티셔츠",
        "quantity": 2,
        "cancelledQuantity": 0,
        "returnedQuantity": 0,
        "remainingQuantity": 2,
        "unitPrice": 29000,
        "subtotal": 58000,
        "cancelledAmount": 0,
        "returnedAmount": 0,
        "status": "ORDERED",
        "statusLabel": "주문완료",
        "statusBadgeClass": "bg-blue-100 text-blue-800",
        "returnReason": null,
        "rejectReason": null,
        "pendingReturnQuantity": 0,
        "returnRequestedAt": null,
        "returnedAt": null
      }
    ],
    "orderDate": "2026-04-06T14:30:00",
    "paidAt": "2026-04-06T14:30:01",
    "shippedAt": null,
    "deliveredAt": null,
    "cancelledAt": null
  }
}
```

### 내 주문 목록 조회

```
GET /api/v1/orders?page=0
```

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 페이지 번호 (0-based) |

**응답 (200 OK)** — `PageResponse<OrderSummaryResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "orderId": 100,
        "orderNumber": "ORD-20260406-001",
        "orderStatus": "PAID",
        "totalAmount": 87000,
        "discountAmount": 5000,
        "shippingFee": 0,
        "finalAmount": 82000,
        "itemCount": 3,
        "orderDate": "2026-04-06T14:30:00"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 25,
    "totalPages": 3,
    "first": true,
    "last": false
  }
}
```

### 주문 상세 조회

```
GET /api/v1/orders/{orderId}
```

**응답 (200 OK)** — `OrderDetailResponse` (주문 생성 응답과 동일 구조)

### 주문 취소

```
POST /api/v1/orders/{orderId}/cancel
```

**Headers (선택)**

```
X-Idempotency-Key: {UUID}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

### 부분 취소

```
POST /api/v1/orders/{orderId}/partial-cancel
```

**Headers (선택)**

```
X-Idempotency-Key: {UUID}
```

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderItemId` | Long | O | 취소할 주문 항목 ID |
| `quantity` | Integer | O | 취소할 수량 (최소 1) |

```json
{
  "orderItemId": 1,
  "quantity": 1
}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

### 반품 신청

```
POST /api/v1/orders/{orderId}/return
```

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `orderItemId` | Long | O | 반품할 주문 항목 ID |
| `quantity` | Integer | O | 반품할 수량 (최소 1) |
| `returnReason` | String | O | 반품 사유: `DEFECT`, `WRONG_ITEM`, `CHANGE_OF_MIND`, `SIZE_ISSUE`, `OTHER` |

```json
{
  "orderItemId": 1,
  "quantity": 1,
  "returnReason": "SIZE_ISSUE"
}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

---

## 쿠폰 (Coupon)

> 모든 엔드포인트 인증 필요

### 쿠폰 발급

```
POST /api/v1/coupons/issue/{couponId}
```

**Headers (선택)**

```
X-Idempotency-Key: {UUID}
```

선착순 이벤트 등 동시 요청이 많은 시나리오에서 멱등성 키 사용을 권장한다.

**응답 (201 Created)**

```json
{
  "success": true
}
```

---

## 위시리스트 (Wishlist)

> 모든 엔드포인트 인증 필요

### 위시리스트 조회

```
GET /api/v1/wishlist?page=0
```

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 페이지 번호 (0-based) |

**응답 (200 OK)** — `PageResponse<WishlistItemResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "wishlistId": 1,
        "productId": 10,
        "productName": "반팔 티셔츠",
        "price": 29000,
        "thumbnailUrl": "/images/product1.jpg",
        "inStock": true,
        "addedAt": "2026-04-01T09:15:00"
      }
    ],
    "page": 0,
    "size": 40,
    "totalElements": 5,
    "totalPages": 1,
    "first": true,
    "last": true
  }
}
```

### 위시리스트 토글

```
POST /api/v1/wishlist/toggle?productId=10
```

위시리스트에 없으면 추가, 있으면 제거한다.

**Query Parameters**

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `productId` | Long | 상품 ID |

**응답 (200 OK)**

```json
{
  "success": true,
  "data": {
    "wishlisted": true
  }
}
```

`wishlisted`가 `true`이면 추가됨, `false`이면 제거됨.

---

## 리뷰 (Review)

### 상품별 리뷰 목록 조회 (공개)

```
GET /api/v1/products/{productId}/reviews?page=0
```

인증 불필요.

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 페이지 번호 (0-based) |

**응답 (200 OK)** — `PageResponse<ReviewResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "reviewId": 1,
        "productId": 10,
        "userId": 5,
        "username": "홍길동",
        "rating": 5,
        "title": "매우 만족합니다",
        "content": "품질이 좋고 배송이 빨라요.",
        "helpfulCount": 12,
        "createdAt": "2026-04-01T10:00:00"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 42,
    "totalPages": 5,
    "first": true,
    "last": false
  }
}
```

### 리뷰 작성 (인증 필요)

```
POST /api/v1/reviews
```

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `productId` | Long | O | 상품 ID |
| `orderItemId` | Long | O | 주문 항목 ID (구매 확인용) |
| `rating` | int | O | 평점 (1~5) |
| `title` | String | — | 리뷰 제목 (최대 200자, 공백만 불가) |
| `content` | String | — | 리뷰 내용 (최대 5,000자, 공백만 불가) |

```json
{
  "productId": 10,
  "orderItemId": 1,
  "rating": 5,
  "title": "매우 만족합니다",
  "content": "품질이 좋고 배송이 빨라요."
}
```

**응답 (201 Created)** — `ReviewResponse`

### 리뷰 삭제 (인증 필요, 본인만)

```
DELETE /api/v1/reviews/{reviewId}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

### "도움이 돼요" 토글 (인증 필요)

```
POST /api/v1/reviews/{reviewId}/helpful
```

**응답 (200 OK)**

```json
{
  "success": true,
  "data": {
    "helpful": true
  }
}
```

`helpful`이 `true`이면 등록됨, `false`이면 해제됨.

---

## 포인트 (Point)

> 모든 엔드포인트 인증 필요

### 내 포인트 이력 조회

```
GET /api/v1/points/history?page=0
```

적립(EARN), 사용(USE), 환불(REFUND), 만료(EXPIRE), 조정(ADJUST) 등 모든 유형의 포인트 변동 내역을 최신순으로 반환한다.

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 페이지 번호 (0-based) |

**응답 (200 OK)** — `PageResponse<PointHistoryResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "historyId": 1,
        "changeType": "EARN",
        "amount": 820,
        "balanceAfter": 1820,
        "referenceType": "ORDER",
        "referenceId": 100,
        "description": "주문 적립",
        "createdAt": "2026-04-06T14:30:00"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 15,
    "totalPages": 2,
    "first": true,
    "last": false
  }
}
```

---

## 사용자 (User)

### 회원가입 (공개)

```
POST /api/v1/users/signup
```

인증 불필요.

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `username` | String | O | 사용자명 (4~50자) |
| `email` | String | O | 이메일 |
| `password` | String | O | 비밀번호 (8자 이상, 영문+숫자+특수문자) |
| `name` | String | O | 이름 |
| `phone` | String | — | 전화번호 (010-xxxx-xxxx 형식) |

```json
{
  "username": "hong",
  "email": "hong@example.com",
  "password": "Pass1234!",
  "name": "홍길동",
  "phone": "010-1234-5678"
}
```

**응답 (201 Created)** — `UserProfileResponse`

```json
{
  "success": true,
  "data": {
    "userId": 1,
    "username": "hong",
    "email": "hong@example.com",
    "name": "홍길동",
    "phone": "010-1234-5678",
    "tierName": "BRONZE",
    "tierLevel": 1,
    "totalSpent": 0,
    "pointBalance": 0,
    "createdAt": "2026-04-06T15:00:00"
  }
}
```

### 내 프로필 조회 (인증 필요)

```
GET /api/v1/users/me
```

**응답 (200 OK)** — `UserProfileResponse` (회원가입 응답과 동일 구조)

### 프로필 수정 (인증 필요)

```
PUT /api/v1/users/me/profile
```

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | String | O | 이름 (최대 50자) |
| `email` | String | O | 이메일 |
| `phone` | String | — | 전화번호 (010-xxxx-xxxx 형식) |

```json
{
  "name": "홍길동",
  "email": "newemail@example.com",
  "phone": "010-9876-5432"
}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

### 비밀번호 변경 (인증 필요)

```
POST /api/v1/users/me/password
```

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `currentPassword` | String | O | 현재 비밀번호 |
| `newPassword` | String | O | 새 비밀번호 (8~100자, 영문+숫자+특수문자) |

```json
{
  "currentPassword": "OldPass1!",
  "newPassword": "NewPass2@"
}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

---

## 재고 관리 (Inventory) — 관리자 전용

> 모든 엔드포인트 ROLE_ADMIN 필요

### 상품별 재고 변경 이력 조회

```
GET /api/v1/admin/inventory/{productId}/history?page=0
```

**Query Parameters**

| 파라미터 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `page` | int | `0` | 페이지 번호 (0-based) |

**응답 (200 OK)** — `PageResponse<InventoryHistoryResponse>`

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "historyId": 1,
        "productId": 10,
        "changeType": "IN",
        "changeAmount": 50,
        "beforeQuantity": 100,
        "afterQuantity": 150,
        "reason": "정기 입고",
        "referenceId": null,
        "createdBy": 1,
        "createdAt": "2026-04-06T10:00:00"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 30,
    "totalPages": 3,
    "first": true,
    "last": false
  }
}
```

### 수동 재고 조정

```
POST /api/v1/admin/inventory/{productId}/adjust
```

양수 amount는 입고, 음수 amount는 출고를 의미한다.

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `amount` | Integer | O | 조정 수량 (양수=입고, 음수=출고) |
| `reason` | String | O | 변경 사유 |

```json
{
  "amount": 50,
  "reason": "정기 입고"
}
```

**응답 (200 OK)**

```json
{
  "success": true
}
```

---

## 엔드포인트 요약

| 메서드 | 경로 | 인증 | 멱등성 키 | 설명 |
|---|---|---|---|---|
| GET | `/api/v1/search` | - | - | 상품 검색 |
| GET | `/api/v1/search/popular` | - | - | 인기 검색어 조회 |
| GET | `/api/v1/products` | - | - | 상품 목록 조회 |
| GET | `/api/v1/products/{id}` | - | - | 상품 상세 조회 |
| GET | `/api/v1/cart` | O | - | 장바구니 조회 |
| POST | `/api/v1/cart` | O | - | 장바구니 추가 |
| PUT | `/api/v1/cart/{productId}` | O | - | 장바구니 수량 변경 |
| DELETE | `/api/v1/cart/{productId}` | O | - | 장바구니 상품 제거 |
| POST | `/api/v1/orders` | O | 지원 | 주문 생성 |
| GET | `/api/v1/orders` | O | - | 내 주문 목록 |
| GET | `/api/v1/orders/{id}` | O | - | 주문 상세 조회 |
| POST | `/api/v1/orders/{id}/cancel` | O | 지원 | 주문 취소 |
| POST | `/api/v1/orders/{id}/partial-cancel` | O | 지원 | 부분 취소 |
| POST | `/api/v1/orders/{id}/return` | O | - | 반품 신청 |
| POST | `/api/v1/coupons/issue/{couponId}` | O | 지원 | 쿠폰 발급 |
| GET | `/api/v1/wishlist` | O | - | 위시리스트 조회 |
| POST | `/api/v1/wishlist/toggle` | O | - | 위시리스트 토글 |
| GET | `/api/v1/products/{id}/reviews` | - | - | 상품 리뷰 목록 |
| POST | `/api/v1/reviews` | O | - | 리뷰 작성 |
| DELETE | `/api/v1/reviews/{id}` | O | - | 리뷰 삭제 |
| POST | `/api/v1/reviews/{id}/helpful` | O | - | 도움이 돼요 토글 |
| GET | `/api/v1/points/history` | O | - | 내 포인트 이력 |
| POST | `/api/v1/users/signup` | - | - | 회원가입 |
| GET | `/api/v1/users/me` | O | - | 내 프로필 조회 |
| PUT | `/api/v1/users/me/profile` | O | - | 프로필 수정 |
| POST | `/api/v1/users/me/password` | O | - | 비밀번호 변경 |
| GET | `/api/v1/admin/inventory/{id}/history` | ADMIN | - | 재고 변경 이력 |
| POST | `/api/v1/admin/inventory/{id}/adjust` | ADMIN | - | 수동 재고 조정 |

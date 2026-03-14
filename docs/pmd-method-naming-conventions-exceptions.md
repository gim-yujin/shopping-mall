# 결정기록: PMD MethodNamingConventions 예외

대상 독자: 신규 개발자

## 배경
Spring Data JPA 파생 쿼리 메서드는 연관 엔티티 경로를 나타내기 위해 메서드명에 `_`를 사용할 수 있다.
예) `findByUserIdAndProduct_ProductId`

PMD `MethodNamingConventions`의 기본 camelCase 제약은 위 패턴을 위반으로 간주한다.
이에 따라 repository 계층의 파생 쿼리 시그니처에 한해 예외를 적용한다.

## 위반 수집 대상
- 경로: `src/main/java/**/repository/*Repository.java`
- 수집 기준: 메서드명에 `_`를 포함한 시그니처

## 수집 결과
- `src/main/java/com/shop/domain/cart/repository/CartRepository.java`
  - `findByUserIdAndProduct_ProductId(Long userId, Long productId)`
  - `deleteByUserIdAndProduct_ProductId(Long userId, Long productId)`
- `src/main/java/com/shop/domain/coupon/repository/UserCouponRepository.java`
  - `existsByUserIdAndCoupon_CouponId(Long userId, Integer couponId)`
- `src/main/java/com/shop/domain/order/repository/OrderItemRepository.java`
  - `findByOrder_OrderId(Long orderId)`
- `src/main/java/com/shop/domain/product/repository/ProductImageRepository.java`
  - `findByProduct_ProductIdOrderByImageOrderAsc(Long productId)`
  - `deleteByProduct_ProductId(Long productId)`
  - `countByProduct_ProductId(Long productId)`
- `src/main/java/com/shop/domain/wishlist/repository/WishlistRepository.java`
  - `findByUserIdAndProduct_ProductId(Long userId, Long productId)`
  - `existsByUserIdAndProduct_ProductId(Long userId, Long productId)`
  - `deleteByUserIdAndProduct_ProductId(Long userId, Long productId)`

총 10건.

## 적용 원칙
- Repository(데이터 접근 계층)에서 Spring Data 파생 쿼리 네이밍은 예외 허용.
- 도메인 서비스/일반 클래스는 기존 네이밍 규칙(camelCase)을 유지.

## 적용 위치
- `config/pmd/ruleset.xml`
  - `MethodNamingConventions` 규칙 추가
  - `violationSuppressRegex`로 파생 쿼리 패턴 예외 처리

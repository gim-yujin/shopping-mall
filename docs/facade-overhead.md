코드를 전부 분석했습니다. 현재 프로젝트의 교차 의존 구조를 정리하고, Facade 도입의 비용과 효과를 비교해 보겠습니다.

---

## 현재 상태 진단

현재 교차 도메인 의존을 정리하면 이렇습니다.

**컨트롤러 계층 (뷰 모델 조립)**
| Controller | 소속 도메인 | 참조 타 도메인 |
|---|---|---|
| MyPageController | user | order, review, coupon, point |
| CartController | cart | user, order |
| ProductController | product | category, review, wishlist |
| AdminController | product | order, category, coupon |
| CategoryController | category | product |

**서비스 계층 (비즈니스 로직)**
- OrderCreationService → cart, coupon, inventory, point, product, user (핵심 유스케이스, 불가피)
- ReviewService → order, product
- 나머지 서비스 간 의존은 단방향, 순환 없음

**스케줄러/이벤트**
- TierScheduler(user) → `OrderRepository` 직접 import
- ProductStockChangedEventListener(product) → `order.event.ProductStockChangedEvent` import

**핵심 사실: 순환 의존이 없습니다.** README에 명시된 대로 모든 흐름이 단방향입니다. 이건 이미 잘 설계된 구조입니다.

---

## Facade 도입 비용-효과 분석

### Facade가 해결하려는 문제 vs 실제 상황

제안하신 6개 Query Facade + 1개 Command Facade의 핵심 가치는 "컨트롤러가 타 도메인 서비스를 직접 import하지 않는다"입니다. 그런데 이 프로젝트에서 컨트롤러의 교차 의존이 하는 일을 보면:

```java
// MyPageController.myPage() — 실질적으로 하는 일
model.addAttribute("recentOrders", orderService.getOrdersByUser(...));
model.addAttribute("coupons", couponService.getAvailableCoupons(...));
```

이건 **비즈니스 로직 조정(coordination)이 아니라 뷰 모델 조립**입니다. SSR 앱에서 컨트롤러가 여러 서비스를 호출해 화면에 필요한 데이터를 모으는 건 자연스러운 역할이에요. Facade를 넣으면:

```
Controller → MyPageQueryFacade → OrderService, CouponService, ReviewService
```

동일한 의존 목록이 한 칸 아래로 이동할 뿐, **시스템 전체의 결합도 총량은 줄지 않습니다.** 대신 7개 파일이 추가되고, 서비스 메서드 시그니처가 바뀔 때마다 Facade도 함께 수정해야 하는 **유지보수 표면적**이 늘어납니다.

### 규모 대비 오버헤드

프로젝트가 Java 파일 135개, 소스 약 10,700줄입니다. 이 규모에서 Facade 7개를 추가하면 전체 대비 약 5%의 순수 위임(delegation) 코드가 생깁니다. 마이크로서비스로 분리하거나 도메인별 모듈 빌드가 예정되어 있다면 의미가 있지만, 단일 모놀리스 SSR 앱에서는 과잉 설계에 해당합니다.

---

## 제 권장: 선택적 적용

전면 Facade 도입 대신, **실제 문제가 있는 2곳만 정리**하는 것을 추천합니다.

### 1. ProductStockChangedEvent → `global.event`로 이동 (강력 추천)

이건 순수한 개선입니다. 현재 product 도메인이 order 도메인의 이벤트 클래스를 import하는 구조는 이벤트의 본래 목적(발행자-구독자 디커플링)과 모순됩니다. `com.shop.global.event`로 옮기면 양쪽 도메인 모두 global만 의존하게 됩니다. 비용 제로, 효과 확실합니다.

### 2. TierScheduler의 OrderRepository 직접 참조 제거 (강력 추천)

이건 진짜 문제입니다. user 도메인의 스케줄러가 order 도메인의 **리포지토리**를 직접 가져다 쓰고 있습니다. 서비스가 아닌 리포지토리를 교차 참조하는 건 도메인 경계를 인프라 수준에서 무너뜨리는 것이에요.

해결 방법으로는 Facade보다 **인터페이스 분리**가 더 가볍습니다:

```java
// user 도메인에 정의
package com.shop.domain.user.port;

public interface UserTierOrderPort {
    Map<Long, BigDecimal> findYearlySpentByUser(LocalDateTime start, LocalDateTime end);
}
```

```java
// order 도메인에서 구현
@Component
public class UserTierOrderAdapter implements UserTierOrderPort {
    private final OrderRepository orderRepository;
    // ...
}
```

이렇게 하면 user 도메인은 자기 안에 정의된 인터페이스만 의존하고, order 도메인이 그 계약을 구현합니다. 의존 방향이 `order → user(port)`로 뒤집혀서 user → order import가 완전히 사라집니다.

### 3. 컨트롤러 Facade는 도입하지 않음 (권장)

MyPageController, CartController, ProductController, CategoryController, AdminController의 교차 의존은 뷰 모델 조립이라는 컨트롤러의 정당한 역할 범위 안에 있습니다. 현재 코드가 읽기 쉽고, 테스트하기 쉽고, 순환도 없습니다.

### 4. 의존성 점검 스크립트는 유지 (좋은 아이디어)

다만 규칙을 "양방향 엣지 금지"로 설정하되, 컨트롤러 계층의 교차 조회는 허용 목록에 넣으세요. 스크립트가 서비스/리포지토리 계층의 순환만 잡도록 하면 실용적입니다.

---

## 요약

| 항목 | 권장 | 이유 |
|---|---|---|
| Event를 global로 이동 | ✅ 적용 | 비용 0, 이벤트 디커플링 본래 목적 달성 |
| TierScheduler 포트/어댑터 | ✅ 적용 | 리포지토리 교차 참조는 실제 문제 |
| 컨트롤러 Query Facade 6개 | ❌ 불필요 | 뷰 모델 조립은 컨트롤러의 역할, 위임 계층만 증가 |
| AdminOrderCommandFacade | ❌ 불필요 | AdminController→OrderService는 자연스러운 조정 |
| 의존성 점검 스크립트 | ✅ 적용 | 서비스/리포지토리 계층 순환 감시에 유용 |

현재 코드 구조가 이미 잘 정리되어 있습니다. 전면 Facade는 "아키텍처적 정확성"은 높이지만 이 프로젝트 규모에서는 실용적 가치보다 유지보수 비용이 더 큽니다. 실제 문제가 있는 2곳(이벤트 위치, 스케줄러 리포지토리 참조)만 정리하는 게 가장 효율적인 방향입니다.


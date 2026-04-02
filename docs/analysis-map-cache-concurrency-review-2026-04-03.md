# Map/Cache 동시성 검토

> **범위**: `HashMap`, `LinkedHashMap`, `ConcurrentHashMap`, `ConcurrentMapCache`,
> `ConcurrentMapCacheManager`, `Hashtable` 사용처와 동시성 영향 검토.
> 코드 검색, 구현 맥락 판독, 관련 테스트 실행 결과를 함께 정리한다.

---

## 1. 결론

- 현재 코드베이스에서 위 컬렉션/캐시 사용으로 인한 **확인된 동시성 버그는 없다**.
- `Hashtable`은 사용처가 없다.
- `ConcurrentHashMap`은 직접 `import`하거나 `new ConcurrentHashMap(...)`로 생성한 코드가 없다.
- `HashMap`과 `LinkedHashMap`의 대부분은 메서드 로컬 변수이므로 요청 간 공유 mutable state가 아니다.
- 실제 동시성 경합이 있는 캐시 갱신 경로는 원자 연산(`merge`, `compute`) 또는 명시적 락으로 보호된다.

---

## 2. 조사 방법

정확한 사용처 확인을 위해 아래 문자열을 코드베이스 전체에서 검색했다.

- `HashMap`
- `LinkedHashMap`
- `ConcurrentHashMap`
- `ConcurrentMapCache`
- `ConcurrentMapCacheManager`
- `Hashtable`

검색 후 각 사용처를 직접 열어 다음 기준으로 분류했다.

- 메서드 로컬 변수인지
- 정적 필드처럼 여러 요청이 공유하는 상태인지
- 공유 상태라면 읽기 전용인지, 쓰기 경합이 있는지
- 실제 운영 경로인지, 테스트 전용 경로인지

---

## 3. 프로덕션 코드 사용처

### 3-1. 컬렉션 직접 사용

| 타입 | 위치 | 범위 | 동시성 판단 |
|---|---|---|---|
| `HashMap` | `src/main/java/com/shop/domain/order/adapter/UserTierOrderAdapter.java` | 메서드 로컬 | 안전. `findYearlySpentByUser()` 내부에서 생성 후 반환하며, 호출 측에서 읽기 전용으로 사용한다. |
| `LinkedHashMap` | `src/main/java/com/shop/domain/order/service/CheckoutPreviewService.java` | 메서드 로컬 | 안전. 요청별 체크아웃 프리뷰 구성용 임시 맵이다. |
| `LinkedHashMap` | `src/main/java/com/shop/domain/order/service/OrderStockProcessor.java` | 메서드 로컬 | 안전. 단일 주문 처리 트랜잭션 내부에서만 사용한다. |
| `LinkedHashMap` | `src/main/java/com/shop/domain/order/service/stock/V1PessimisticLockStockDeduction.java` | 메서드 로컬 | 안전. 비관적 잠금으로 조회한 상품을 정렬된 키 순서로 매핑하는 임시 맵이다. |
| `LinkedHashMap` | `src/main/java/com/shop/domain/order/service/stock/V2OptimisticRetryStockDeduction.java` | 메서드 로컬 | 안전. 재시도 루프 내부의 로컬 맵이며 공유되지 않는다. |
| `LinkedHashMap` | `src/main/java/com/shop/domain/order/entity/OrderStatus.java` | 메서드 로컬 반환값 | 안전. `labelsByCode()`, `badgeClassesByCode()`가 호출마다 새 맵을 만든다. |
| `LinkedHashMap` | `src/main/java/com/shop/global/exception/DuplicateConstraintMessageResolver.java` | 정적 필드 | 현재 안전. 클래스 초기화 블록에서 채운 뒤 읽기만 한다. 다만 타입은 mutable이므로 방어적으로 불변화할 여지는 있다. |
| `LinkedHashMap` | `src/main/java/com/shop/global/exception/GlobalExceptionHandler.java` | 정적 필드 | 현재 안전. 클래스 초기화 후 `entrySet()`/`keySet()` 순회만 수행한다. 쓰기 경로는 없다. |
| `ConcurrentHashMap` | 사용처 없음 | 없음 | 직접 사용 코드가 없다. |
| `Hashtable` | 사용처 없음 | 없음 | 직접 사용 코드가 없다. |

### 3-2. 관련 캐시 원자 연산 경로

`ConcurrentHashMap`을 직접 쓰지는 않지만, 실제 동시성 경합 가능성이 있는 캐시 갱신 경로는 아래와 같다.

| 위치 | 구현 | 동시성 판단 |
|---|---|---|
| `src/main/java/com/shop/domain/review/service/ReviewService.java` | `CaffeineCache`인 경우 `asMap().merge(productId, 1L, ...)` | 안전. 키 단위 원자적 증가를 사용한다. |
| `src/main/java/com/shop/domain/review/service/ReviewService.java` | non-Caffeine fallback에서 `synchronized (this)` + `get/put` | 안전. 다만 fallback이 실제 운영 경로라면 서비스 전체 bump를 직렬화한다. 현재 운영 기본 경로는 아님. |
| `src/main/java/com/shop/global/security/LoginAttemptService.java` | `CaffeineCache`인 경우 `asMap().compute(cacheKey, ...)` | 안전. 동일 키에 대한 실패 횟수 증가가 원자적으로 수행된다. |
| `src/main/java/com/shop/global/security/LoginAttemptService.java` | non-Caffeine fallback에서 bucketized lock(`fallbackLocks`) + `get/put` | 안전. 전역 `synchronized(this)`가 아니라 키 해시 기반 버킷 락을 사용한다. |

### 3-3. 운영 캐시 설정 확인

운영 `CacheManager`는 `ConcurrentMapCacheManager`가 아니라 `SimpleCacheManager + CaffeineCache` 구성이다.

- `src/main/java/com/shop/global/config/CacheConfig.java`
- `productReviewVersion`, `loginAttempts` 모두 Caffeine 기반 캐시로 등록되어 있다.

즉, `ReviewService`와 `LoginAttemptService`의 실제 운영 경로는 non-Caffeine fallback이 아니라 Caffeine 원자 연산 경로다.

---

## 4. 테스트 전용 사용처

아래 사용처는 테스트 코드이며, 운영 동시성 버그의 직접 원인은 아니다.

### 4-1. `HashMap`

- `src/test/java/com/shop/domain/order/service/OrderOversellingTest.java`
- `src/test/java/com/shop/domain/product/service/ProductServiceIntegrationTestSupplementary.java`

### 4-2. `LinkedHashMap`

- `src/test/java/com/shop/domain/order/controller/OrderControllerUnitTest.java`
- `src/test/java/com/shop/global/cache/CacheTtlOptimizationTest.java`
- `src/test/java/com/shop/domain/order/service/CheckoutPreviewServiceUnitTest.java`

### 4-3. `ConcurrentMapCache` / `ConcurrentMapCacheManager`

- `src/test/java/com/shop/domain/review/service/ReviewServiceBranchTest.java`
- `src/test/java/com/shop/global/security/SecurityBranchTest.java`
- `src/test/java/com/shop/global/security/LoginAttemptServiceTest.java`
- `src/test/java/com/shop/global/security/LoginAttemptServiceBranchTest.java`
- `src/test/java/com/shop/global/cache/CacheMetricsBranchTest.java`

이 테스트들은 주로 non-Caffeine fallback 분기 또는 경계 조건을 검증하기 위해 `ConcurrentMapCache` 계열을 사용한다.

---

## 5. 동시성 관점 상세 판단

### 5-1. 동시성 문제가 없는 이유

1. `HashMap`/`LinkedHashMap` 대부분이 메서드 로컬이다.
2. 메서드 로컬 맵은 호출 스택 안에서만 존재하므로 요청 간 공유되지 않는다.
3. 정적 `LinkedHashMap` 2개는 클래스 초기화 후 읽기만 하므로 concurrent read만 발생한다.
4. 실제 경합 가능성이 있는 캐시 상태 변경은 `merge`, `compute`, 명시적 락으로 보호된다.

### 5-2. 주의가 필요한 지점

현재 버그는 아니지만, 아래는 방어적 개선 후보로 볼 수 있다.

- `DuplicateConstraintMessageResolver.KEYWORD_TO_MESSAGE`
- `GlobalExceptionHandler.REDIRECT_PATH_POLICY`

두 필드는 실제로는 읽기 전용이므로 `Collections.unmodifiableMap(...)` 또는 불변 컬렉션으로 감싸면 의도를 더 명확히 표현할 수 있다. 다만 현 상태만으로 동시성 결함이 발생하는 것은 아니다.

또한 `ReviewService`의 non-Caffeine fallback은 안전하지만 `synchronized (this)` 기반이라 높은 경합에서 확장성은 떨어질 수 있다. 현재 운영 캐시 설정에서는 이 경로를 타지 않으므로 우선순위는 낮다.

---

## 6. 검증 근거

관련 테스트를 실제로 실행했고 성공했다.

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew test \
  --tests 'com.shop.global.security.LoginAttemptServiceTest' \
  --tests 'com.shop.global.security.LoginAttemptServiceBranchTest' \
  --tests 'com.shop.global.cache.CacheConsistencyConcurrencyTest' \
  --tests 'com.shop.domain.review.service.ReviewServiceBranchTest' \
  --no-daemon
```

결과:

- `BUILD SUCCESSFUL`

이 테스트들은 다음 사실을 뒷받침한다.

- `LoginAttemptService`의 동일 키 병렬 실패 누적이 유실되지 않는다.
- `ReviewService`가 의존하는 리뷰 버전 bump의 원자적 증가 경로가 기대한 횟수를 유지한다.
- non-Caffeine fallback 분기가 예외 없이 동작한다.

---

## 7. 최종 판단

이번 검토 기준으로는 `Hashtable`, `HashMap`, `LinkedHashMap`, `ConcurrentHashMap`,
`ConcurrentMapCache`, `ConcurrentMapCacheManager` 사용처 중 **즉시 수정이 필요한 동시성 문제는 없다**.

실제 운영 경로에서 중요한 공유 상태 변경은 이미 원자 연산 또는 락으로 보호되고 있으며,
직접 컬렉션 사용 대부분은 요청 로컬 또는 테스트 로컬 범위에 머물러 있다.

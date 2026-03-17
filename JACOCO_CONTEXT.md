# 운영가이드: JaCoCo 커버리지 작업 컨텍스트

대상 독자: 신규 개발자

## 현재 상태

### 설정 완료

`build.gradle`에 JaCoCo 플러그인이 추가되었다.

- **플러그인**: `id 'jacoco'` (plugins 블록), toolVersion `0.8.12`
- **리포트**: `./gradlew test` 실행 시 `finalizedBy jacocoTestReport`로 HTML/XML 자동 생성
- **리포트 경로**: `build/reports/jacoco/test/html/index.html`
- **측정 제외 대상**: `**/config/**`, `**/dto/**`, `**/ShopApplication.class`, `**/scheduler/**`
- **최소 기준**: `jacocoTestCoverageVerification` — LINE COVEREDRATIO **60%** (2025-03 상향, 이전 30%)

### 실행 명령어

```bash
# 테스트 실행 → 커버리지 리포트 자동 생성
./gradlew test

# 리포트 확인
open build/reports/jacoco/test/html/index.html

# 최소 커버리지 기준 검증 (CI용)
./gradlew jacocoTestCoverageVerification
```

### 최신 커버리지 수치 (2025-03 6차 기준)

- **전체 Instructions: 88%, Branches: 76%**
- **100% 패키지: 16개**
- **50% 미만 패키지: 0개**
- **최하위 패키지: wishlist.controller 61%**

---

## 프로젝트 테스트 현황

### 규모

- 프로덕션 소스: 153개 `.java` 파일
- 테스트 소스: 106개 `.java` 파일 (TestDataFactory 등 지원 파일 포함) — 이전 99개에서 7개 추가

### 커버리지 개선 이력

| 단계 | 전체 Instr% | Branch% | 100% 패키지 | 50%미만 | 추가 테스트 |
|---|---|---|---|---|---|
| 시작 | 72% | 68% | 7개 | 8개 | — |
| +OrderController | 76% | 70% | 9개 | 7개 | 2파일 29테스트 |
| +API/쿠폰 컨트롤러 | 81% | 70% | 11개 | 6개 | 5파일 36테스트 |
| +SSR/어댑터 | 84% | 72% | 15개 | 2개 | 4파일 25테스트 |
| +user.controller/coupon.service | 88% | 73% | 17개 | 0개 | 2파일 35테스트 |
| +핵심 클래스 직접 단위 테스트 | 87% | 75% | 16개 | 0개 | 6파일 48테스트 |
| **+product.service Branch 보강** | **88%** | **76%** | **16개** | **0개** | **1파일 12테스트** |

총 추가 (전체 이력): **20개 테스트 파일, ~185개 테스트 케이스**

### 6차 추가 테스트 파일 목록 (최신 세션)

| 파일 | 테스트 수 | 커버 대상 |
|---|---|---|
| `OrderCreationServiceUnitTest` | 23 | 주문 생성 전 분기 (재고/쿠폰/포인트/등급/배송비/장바구니) |
| `OrderInvariantValidatorTest` | 6 | 불변식 검증 3가지 + 경계값 |
| `ProductApiControllerUnitTest` | 5 | REST API 목록/상세/정렬/품절 |
| `RateLimitServiceTest` | 6 | 인증/비인증/플랜독립/사용자독립/용량 |
| `CustomUserDetailsServiceTest` | 4 | 정상/미존재/비활성/정규화 |
| `LoginBlockPreAuthenticationFilterTest` | 4 | 차단/비차단/비로그인/GET |
| `ProductServiceBranchCoverageTest` | 12 | search null, cached 조회, 이미지 CRUD 분기 |

### 도메인별 테스트 커버리지 맵 (업데이트)

| 도메인 | 서비스 클래스 | 단위 테스트 | 통합 테스트 | 동시성 테스트 | 컨트롤러 테스트 |
|---|---|---|---|---|---|
| cart | CartService | ✓ | ✓ | ✓ | ✓ (SSR + API) |
| category | CategoryService | ✓ (+ Supplementary) | ✓ (+ Supplementary) | — | ✓ |
| coupon | CouponService | ✓ (+ Supplementary) | ✓ | ✓ | ✓ (SSR + Admin) |
| inventory | InventoryService | ✓ (+ Supplementary) | ✓ | — | — |
| order | OrderService | ✓ | ✓ (+ Supplementary) | ✓ (5개) | ✓ (SSR + API) |
| order | OrderCreationService | **✓ (신규 23개)** | 간접 | ✓ | — |
| order | OrderCancellationService | ✓ | — | ✓ | — |
| order | OrderQueryService | — | — (ReturnTest만) | — | — |
| order | PartialCancellationService | ✓ | ✓ | ✓ | — |
| order | ShippingFeeCalculator | ✓ | — | — | — |
| order | UserTierOrderAdapter | ✓ | — | — | — |
| order | **OrderInvariantValidator** | **✓ (신규 6개)** | — | — | — |
| point | PointQueryService | ✓ | — | — | — |
| point | PointChangeTypeLabelMapper | ✓ | — | — | — |
| product | ProductService | ✓ (+ Supplementary + **BranchCoverage**) | ✓ (+ Supplementary) | — | — |
| product | ViewCountService | 없음 | — | — | — |
| product | ProductCacheEvictHelper | 없음 | — | — | — |
| product | ProductStockChangedEventListener | ✓ | — | — | — |
| review | ReviewService | ✓ | ✓ | ✓ | ✓ (SSR + API) |
| search | SearchService | ✓ | ✓ | — | ✓ |
| user | UserService | ✓ | ✓ | ✓ | ✓ (Auth + MyPage) |
| wishlist | WishlistService | — | ✓ | ✓ | ✓ (API) |
| global | **RateLimitService** | **✓ (신규 6개)** | — | — | — |
| global | **CustomUserDetailsService** | **✓ (신규 4개)** | — | — | — |
| global | **LoginBlockPreAuthFilter** | **✓ (신규 4개)** | — | — | — |

### 패키지별 최종 커버리지 (6차 기준)

| 패키지 | Instr% | Branch% | 이전 Instr% | 이전 Branch% | 비고 |
|---|---|---|---|---|---|
| **product.service** | **94%** | **96%** | 74% | 36% | **+20%p / +60%p 대폭 개선** |
| **order.service** | **96%** | **88%** | 93% | 84% | +3%p / +4%p |
| **order.validation** | **97%** | **80%** | 72% | 50% | +25%p / +30%p |
| **product.controller.api** | **100%** | **n/a** | 69% | n/a | +31%p |
| **global.ratelimit** | **90%** | **85%** | — | — | 신규 커버 |
| **global.security** | **88%** | **61%** | 84% | 56% | +4%p / +5%p |
| **product.entity** | **91%** | **87%** | 79% | 75% | +12%p / +12%p |
| order.controller | 72% | 45% | 100% | 100% | — |
| user.controller | 100% | 100% | 100% | 100% | — |
| coupon.service | 100% | 100% | 100% | 100% | — |
| review.controller | 100% | 100% | 100% | 100% | — |
| cart.controller | 100% | n/a | 100% | n/a | — |
| cart.controller.api | 100% | n/a | 100% | n/a | — |
| order.controller.api | 84% | 62% | 100% | n/a | — |
| category.controller | 100% | n/a | 100% | n/a | — |
| inventory.service | 100% | 100% | 100% | 100% | — |
| review.controller.api | 100% | n/a | 100% | n/a | — |
| search.service | 100% | n/a | 100% | n/a | — |
| wishlist.service | 100% | 100% | 100% | 100% | — |
| wishlist.controller.api | 100% | n/a | 100% | n/a | — |
| order.adapter | 100% | 100% | 100% | 100% | — |
| point.controller | 100% | n/a | 100% | n/a | — |
| global.cache | 100% | n/a | 100% | n/a | — |
| global.event | 100% | n/a | 100% | n/a | — |
| coupon.controller | 99% | 83% | 99% | 83% | — |
| search.controller | 97% | 75% | 97% | 75% | — |
| category.service | 97% | 100% | 97% | 100% | — |
| order.entity | 93% | 70% | 93% | 70% | — |
| cart.service | 93% | 75% | 93% | 75% | — |
| point.service | 92% | 88% | 92% | 88% | — |
| coupon.entity | 91% | 78% | 91% | 76% | — |
| user.service | 90% | 72% | 90% | 72% | — |
| wishlist.entity | 88% | n/a | 88% | n/a | — |
| user.entity | 85% | 100% | 85% | 100% | — |
| cart.entity | 81% | n/a | 81% | n/a | — |
| review.entity | 78% | n/a | 78% | n/a | — |
| product.controller | 71% | 54% | 71% | 54% | — |
| inventory.entity | 77% | n/a | 77% | n/a | — |
| global.exception | 75% | 62% | 72% | 62% | — |
| search.entity | 73% | 75% | 73% | 75% | — |
| point.entity | 74% | n/a | 74% | n/a | — |
| global.outbox | 71% | 65% | — | — | — |
| global.common | 70% | 85% | 70% | 85% | — |
| global.idempotency | 69% | 71% | — | — | — |
| category.entity | 62% | n/a | 62% | n/a | — |
| wishlist.controller | 61% | n/a | 61% | n/a | — |

---

## 커버리지 갭 우선순위 (업데이트)

### ~~P1 — 완료~~

- ~~OrderController~~ → 100% ✅
- ~~API 컨트롤러 5개~~ → 전부 100% ✅
- ~~CouponController/AdminCouponController~~ → 99% ✅
- ~~CouponService~~ → 100% ✅
- ~~AuthController/MyPageController~~ → 100% ✅
- ~~OrderCreationService 직접 단위 테스트~~ → 96%/88% ✅
- ~~OrderInvariantValidator~~ → 97%/80% ✅
- ~~ProductApiController~~ → 100% ✅
- ~~RateLimitService~~ → 90%/85% ✅
- ~~CustomUserDetailsService~~ → 88%/61% ✅
- ~~LoginBlockPreAuthenticationFilter~~ → 88%/61% ✅
- ~~product.service Branch 보강~~ → **94%/96%** ✅

### P2 — 선택적 보강 (현재 우선순위 아님)

| 대상 | 현재 | 비고 |
|---|---|---|
| order.controller | 72% (Branch 45%) | SSR 컨트롤러 일부 분기 |
| order.controller.api | 84% (Branch 62%) | 멱등성 관련 분기 |
| global.security (Branch) | 88% (Branch 61%) | LoginAttemptService 내부 분기 |
| global.exception | 75% (Branch 62%) | ApiExceptionHandler 일부 |
| global.outbox | 71% (Branch 65%) | OutboxEventPoller 분기 |
| global.idempotency | 69% (Branch 71%) | 멱등성 레코드 처리 분기 |

### P3 — 낮은 가치 (유지)

| 대상 | 현재 | 이유 |
|---|---|---|
| ViewCountService | 미측정 | 1줄 위임 메서드 |
| ProductCacheEvictHelper | 미측정 | 캐시 키 순회 삭제만 수행 |
| SecurityUtil | 미측정 | 정적 메서드 2~3개 |
| entity 패키지들 (62~81%) | 다양 | getter/setter 위주, JPA 활용 |

---

## 설계 결정 메모

### jacocoTestCoverageVerification 기준 상향 이력

| 시점 | 기준 | 근거 |
|---|---|---|
| 초기 | 30% | 기본 안전망, 기존 빌드 깨지지 않도록 |
| 2025-03 | **60%** | 전체 88%, 최하위 패키지 61%, 안전 마진 1%p 확보 |
| (다음) | 70% | 전체 90%+ 달성 시 검토 |

### check 태스크와의 관계

현재 `check` 태스크는 `test`를 의존성에서 **제거**하고 `checkstyleMain/Test`, `pmdMain/Test`만 실행한다. `jacocoTestCoverageVerification`은 `check`에 포함하지 않았다 — CI에서 별도 단계로 실행하는 것을 권장한다.

### 테스트 패턴 일관성

신규 테스트는 모두 동일한 패턴을 따른다:
- `@ExtendWith(MockitoExtension.class)` + `standaloneSetup`
- `SecurityContextHolder`에 직접 인증 정보 주입 (Spring Security 필터 체인 우회)
- `LocalValidatorFactoryBean` 등록으로 `@Valid` 검증 동작
- `@BeforeEach`에서 컨트롤러 인스턴스 + MockMvc 생성
- `@AfterEach`에서 `SecurityContextHolder.clearContext()`
- Hamcrest 매처 사용 시 `equalTo()` (Mockito `eq()` 혼동 주의)

### 6차 테스트 작성에서 발견된 패턴

- **`List.of()` 불변 리스트 주의**: `OrderCreationService`가 `cartItems.sort()`로 직접 정렬하므로, Mock 반환값에 `List.of()`를 사용하면 `UnsupportedOperationException` 발생. `new ArrayList<>(List.of(...))`로 감싸야 한다.
- **`lenient()` 필요 판단**: 공통 스텁 헬퍼(`stubCommonPath`)에서 설정하는 `orderRepository.save()` 등은 예외 테스트에서 도달하지 않으므로 `lenient()` 필요.
- **`standaloneSetup`에서 예외 핸들러 미등록**: `GlobalExceptionHandler`가 없으므로 `ResourceNotFoundException`이 `ServletException`으로 래핑됨. `assertThatThrownBy(...).hasCauseInstanceOf()`로 검증.
- **`&&` 단축 평가와 UnnecessaryStubbing**: `isLoginRequest()`의 `"POST".equalsIgnoreCase(getMethod()) && ...`에서 getMethod()가 "GET"이면 getServletPath()는 호출되지 않아 스텁이 불필요.

---

## 관련 파일 위치

```
build.gradle                           ← JaCoCo 설정 포함 (minimum 60%)
build/reports/jacoco/test/html/        ← 커버리지 HTML 리포트 (test 실행 후 생성)
build/reports/jacoco/test/jacocoTestReport.xml ← CI 연동용 XML 리포트

src/test/java/com/shop/               ← 전체 테스트 루트
src/test/java/com/shop/testsupport/TestDataFactory.java ← 테스트 픽스처 팩토리
```

---

## 다음 작업 체크리스트

1. [x] `./gradlew test` 실행 → 실제 커버리지 수치 확인 → **88% / 76%**
2. [x] 도메인별/패키지별 커버리지 수치를 기반으로 P1 대상 결정
3. [x] 컨트롤러 계층 전체 테스트 작성 (13개 파일, ~125개 테스트)
4. [x] 커버리지 수치 안정화 후 minimum 기준 상향 → **30% → 60%**
5. [x] 핵심 클래스 직접 단위 테스트 (6개 파일, 48개 테스트)
6. [x] product.service Branch 보강 (1개 파일, 12개 테스트) → **46% → 96%**
7. [ ] `jacocoTestCoverageVerification`을 CI 파이프라인에 추가할지 결정
8. [ ] (선택) order.controller 72%/45% 보강
9. [ ] (선택) SpotBugs 실행 및 결과 분석 (`./gradlew spotbugsMain spotbugsTest -PenableSpotbugs=true`)

# JaCoCo 커버리지 작업 컨텍스트

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

### 최신 커버리지 수치 (2025-03 기준)

- **전체 Instructions: 88%, Branches: 73%**
- **100% 패키지: 17개**
- **50% 미만 패키지: 0개**
- **최하위 패키지: wishlist.controller 61%**

---

## 프로젝트 테스트 현황

### 규모

- 프로덕션 소스: 138개 `.java` 파일
- 테스트 소스: 87개 `.java` 파일 (TestDataFactory 포함) — 이전 76개에서 11개 추가

### 커버리지 개선 이력

| 단계 | 전체 Instr% | Branch% | 100% 패키지 | 50%미만 | 추가 테스트 |
|---|---|---|---|---|---|
| 시작 | 72% | 68% | 7개 | 8개 | — |
| +OrderController | 76% | 70% | 9개 | 7개 | 2파일 29테스트 |
| +API/쿠폰 컨트롤러 | 81% | 70% | 11개 | 6개 | 5파일 36테스트 |
| +SSR/어댑터 | 84% | 72% | 15개 | 2개 | 4파일 25테스트 |
| +user.controller/coupon.service | **88%** | **73%** | **17개** | **0개** | 2파일 35테스트 |

총 추가: **13개 테스트 파일, ~125개 테스트 케이스**

### 도메인별 테스트 커버리지 맵 (업데이트)

| 도메인 | 서비스 클래스 | 단위 테스트 | 통합 테스트 | 동시성 테스트 | 컨트롤러 테스트 |
|---|---|---|---|---|---|
| cart | CartService | ✓ | ✓ | ✓ | ✓ (SSR + API) |
| category | CategoryService | ✓ (+ Supplementary) | ✓ (+ Supplementary) | — | **✓ (신규)** |
| coupon | CouponService | ✓ (+ **Supplementary**) | ✓ | ✓ | ✓ (SSR + **Admin 신규**) |
| inventory | InventoryService | ✓ (+ Supplementary) | ✓ | — | — |
| order | OrderService | ✓ | ✓ (+ Supplementary) | ✓ (5개) | **✓ (SSR + API 신규)** |
| order | OrderCreationService | **없음** | 간접 | ✓ | — |
| order | OrderCancellationService | ✓ | — | ✓ | — |
| order | OrderQueryService | — | — (ReturnTest만) | — | — |
| order | PartialCancellationService | ✓ | ✓ | ✓ | — |
| order | ShippingFeeCalculator | ✓ | — | — | — |
| order | UserTierOrderAdapter | **✓ (신규)** | — | — | — |
| point | PointQueryService | ✓ | — | — | — |
| point | PointChangeTypeLabelMapper | ✓ | — | — | — |
| product | ProductService | ✓ (+ Supplementary) | ✓ (+ Supplementary) | — | — |
| product | ViewCountService | **없음** | — | — | — |
| product | ProductCacheEvictHelper | **없음** | — | — | — |
| product | ProductStockChangedEventListener | ✓ | — | — | — |
| review | ReviewService | ✓ | ✓ | ✓ | **✓ (SSR + API 신규)** |
| search | SearchService | ✓ | ✓ | — | ✓ |
| user | UserService | ✓ | ✓ | ✓ | **✓ (Auth + MyPage 신규)** |
| wishlist | WishlistService | — | ✓ | ✓ | **✓ (API 신규)** |

### 신규 추가된 테스트 파일 목록

| 파일 | 테스트 수 | 커버 대상 |
|---|---|---|
| `OrderControllerUnitTest` | 17 | OrderController(SSR) 전 엔드포인트 |
| `OrderApiControllerUnitTest` | 12 | OrderApiController(REST) 전 엔드포인트 |
| `CartApiControllerUnitTest` | 7 | CartApiController(REST) 전 엔드포인트 |
| `ReviewApiControllerUnitTest` | 8 | ReviewApiController(REST) 전 엔드포인트 |
| `WishlistApiControllerUnitTest` | 4 | WishlistApiController(REST) 전 엔드포인트 |
| `CouponControllerSupplementaryUnitTest` | 4 | CouponController POST 메서드 |
| `AdminCouponControllerUnitTest` | 13 | AdminCouponController 전 엔드포인트 |
| `CategoryControllerUnitTest` | 3 | CategoryController 전 엔드포인트 |
| `ReviewControllerSupplementaryUnitTest` | 12 | ReviewController(SSR) 미커버 경로 |
| `CartControllerUnitTest` | 6 | CartController(SSR) 전 엔드포인트 |
| `UserTierOrderAdapterUnitTest` | 2 | UserTierOrderAdapter 전 메서드 |
| `UserControllerSupplementaryUnitTest` | 16 | AuthController + MyPageController 미커버 경로 |
| `CouponServiceSupplementaryUnitTest` | 19 | CouponService 미커버 전 메서드/분기 |

### 패키지별 최종 커버리지

| 패키지 | Instr% | Branch% | 비고 |
|---|---|---|---|
| order.controller | 100% | 100% | — |
| user.controller | 100% | 100% | — |
| coupon.service | 100% | 100% | — |
| review.controller | 100% | 100% | — |
| cart.controller | 100% | n/a | — |
| cart.controller.api | 100% | n/a | — |
| order.controller.api | 100% | n/a | — |
| category.controller | 100% | n/a | — |
| inventory.service | 100% | 100% | — |
| review.controller.api | 100% | n/a | — |
| search.service | 100% | n/a | — |
| wishlist.service | 100% | 100% | — |
| wishlist.controller.api | 100% | n/a | — |
| order.adapter | 100% | 100% | — |
| point.controller | 100% | n/a | — |
| global.cache | 100% | n/a | — |
| global.event | 100% | n/a | — |
| coupon.controller | 99% | 83% | — |
| search.controller | 97% | 75% | — |
| category.service | 97% | 100% | — |
| order.service | 93% | 84% | 간접 커버 (통합+동시성) |
| order.entity | 93% | 70% | — |
| cart.service | 93% | 75% | — |
| point.service | 92% | 88% | — |
| coupon.entity | 91% | 76% | — |
| user.service | 90% | 72% | — |
| wishlist.entity | 88% | n/a | — |
| user.entity | 85% | 100% | — |
| global.security | 84% | 56% | LoginAttemptService 등 |
| cart.entity | 81% | n/a | — |
| product.entity | 79% | 75% | — |
| review.entity | 78% | n/a | — |
| inventory.entity | 77% | n/a | — |
| point.entity | 74% | n/a | — |
| product.service | 74% | 36% | Branch 보강 여지 있음 |
| review.service | 74% | 81% | — |
| search.entity | 73% | 75% | — |
| global.exception | 72% | 62% | — |
| order.validation | 72% | 50% | — |
| product.controller | 71% | 54% | — |
| global.common | 70% | 85% | — |
| product.controller.api | 69% | n/a | — |
| category.entity | 62% | n/a | — |
| wishlist.controller | 61% | n/a | — |

---

## 커버리지 갭 우선순위 (업데이트)

### ~~P1 — 완료~~

- ~~OrderController~~ → 100% ✅
- ~~API 컨트롤러 5개~~ → 전부 100% ✅
- ~~CouponController/AdminCouponController~~ → 99% ✅
- ~~CouponService~~ → 100% ✅
- ~~AuthController/MyPageController~~ → 100% ✅

### P2 — 선택적 보강 (현재 우선순위 아님)

| 대상 | 현재 | 비고 |
|---|---|---|
| product.service (Branch) | 74% (Branch 36%) | Branch 커버리지가 특히 낮음 |
| product.controller | 71% (Branch 54%) | 상품 목록/상세 분기 |
| global.security | 84% (Branch 56%) | LoginBlockPreAuthenticationFilter 등 |
| global.exception | 72% (Branch 62%) | ApiExceptionHandler 일부 |

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

1. [x] `./gradlew test` 실행 → 실제 커버리지 수치 확인 → **88% / 73%**
2. [x] 도메인별/패키지별 커버리지 수치를 기반으로 P1 대상 결정
3. [x] 컨트롤러 계층 전체 테스트 작성 (13개 파일, ~125개 테스트)
4. [x] 커버리지 수치 안정화 후 minimum 기준 상향 → **30% → 60%**
5. [ ] `jacocoTestCoverageVerification`을 CI 파이프라인에 추가할지 결정
6. [ ] (선택) product.service Branch 커버리지 보강 (36% → 70%+)
7. [ ] (선택) SpotBugs 실행 및 결과 분석 (`./gradlew spotbugsMain spotbugsTest -PenableSpotbugs=true`)

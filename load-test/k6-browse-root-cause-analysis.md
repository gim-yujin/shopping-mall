# k6 Browse 시나리오 원인 분석 (코드 수정 전)

## 1) 관측 요약

- `browse_only` 시나리오에서 `http_req_duration p95 = 12.5s`, `max = 22.4s`, `http_req_failed = 7.87%`로 임계치(`p95 < 3s`, `fail rate < 5%`)를 초과했다.
- 실패 체크는 홈(0 fail) 대비 상품목록/상세/검색/카테고리에서 집중적으로 발생했다.
- 캐시(`bestSellers/newArrivals/deals/topCategories/popularKeywords`)는 약 99.7% hit로 매우 정상적이었다.

## 2) 코드/구조 기반 원인

### 원인 A. `상품 상세` 요청이 읽기+쓰기 결합 구조라 DB 병목을 유발

`/products/{id}`는 상세 조회 시마다 `view_count` 증가 UPDATE를 먼저 실행한다.

- `ProductController.productDetail()` → `productService.findByIdAndIncrementView(productId)` 호출
- `ProductService.findByIdAndIncrementView()`에서
  1. `incrementViewCount(productId)` (UPDATE)
  2. `findByIdWithCategory(productId)` (SELECT)

즉, 단순 browse 트래픽에도 매 상세 요청이 쓰기 트랜잭션을 강제한다. 테스트 스크립트는 상품 ID를 1~10에서 랜덤 선택하므로 소수 행(Hot row)에 UPDATE가 집중되기 쉬운 패턴이며, 이 경우 행 잠금 대기와 커넥션 점유 시간이 늘어나 tail latency가 급증한다.

### 원인 B. DB 커넥션 풀(17) 대비 동시성(최대 100 VU) 과구독

애플리케이션 Hikari 설정은 최대 17 커넥션 fixed-size이고 커넥션 timeout이 5초다. browse 시나리오는 최대 100 VU로 동작한다. 요청당 DB 작업량(상세의 UPDATE, 목록/카테고리/검색 SELECT, 검색 로그 INSERT)이 누적되면 풀 대기열이 형성되고, 일부는 timeout 또는 상위단 예외(500)로 전파될 수 있다.

특히 k6 지표에서

- `connecting/blocked/sending`은 매우 작고,
- `waiting(TTFB)`만 p95가 매우 큼

이라는 패턴은 네트워크보다 서버(주로 앱/DB 처리 구간) 내부 대기가 원인임을 강하게 시사한다.

### 원인 C. `/search`는 조회와 함께 동기 INSERT를 매번 수행

`/search`는 `page == 0`일 때 `searchService.logSearch(...)`를 즉시 저장한다. browse 시나리오는 매 반복마다 검색을 수행하고 page=0이므로, 검색 요청은 읽기 + 쓰기 혼합 트랜잭션이 된다. 쓰기 지점이 늘수록 커넥션 풀 경쟁이 악화될 수 있다.

### 원인 D. 홈 캐시는 정상이나, 병목 엔드포인트에는 직접 효과가 제한적

캐시 대상은 홈 화면 구성 데이터(`bestSellers/newArrivals/deals/topCategories/popularKeywords`) 중심이다. 실패가 집중된 목록/상세/검색/카테고리는 캐시 적용 범위 밖(또는 일부만 간접 영향)이라, 홈 캐시 hit rate가 높아도 전체 browse p95 개선으로 직결되지 않는다.

### 원인 E. 운영 성능 관점에서 불리한 기본 설정 2가지

- `spring.thymeleaf.cache: false` → 템플릿 캐시를 사용하지 않아 서버 렌더링 비용 증가
- `logging.level.com.shop: DEBUG` → 고부하 구간에서 애플리케이션 로그 비용 증가

둘 다 기능 오류를 만들지는 않지만, p95/p99 구간의 지연을 키우는 증폭 요인이 될 수 있다.

## 3) 입력 결과와의 정합성

- 홈(캐시 중심)만 100% 성공이고, DB 부하가 큰 엔드포인트(상세/카테고리/검색/목록)에 실패가 몰린다.
- 지연의 대부분이 서버 처리시간(`http_req_waiting`)에 집중되어 네트워크 병목 가설과 맞지 않는다.
- 캐시 hit는 높은데 성능은 나쁜 현상은 “캐시가 적용된 경로와 실제 병목 경로가 다르다”는 구조와 일치한다.

## 4) 우선순위 결론 (수정 전 분석)

1. **최우선 원인**: 상세 조회 시 `view_count` 동기 UPDATE + hot row 경합
2. **공통 기반 원인**: DB pool 17 vs 동시성 100에서의 과대 경합
3. **가중 원인**: 검색 로그 동기 INSERT, SSR/로그 설정으로 인한 CPU/IO 오버헤드
4. **참고 사항**: 홈 캐시 상태는 양호하며 현재 병목의 주원인은 아님


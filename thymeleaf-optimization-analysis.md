# Thymeleaf 최적화 분석

`fragments/product-card.html`의 페이지네이션 최적화(전체 페이지 순회 대신 현재 페이지 중심 윈도우)와 유사한 관점으로, 템플릿 렌더링 비용 증가 가능성이 있는 구간을 점검했다.

## 1) 전체 페이지 순회형 페이지네이션 잔존

아래 템플릿은 모두 `#numbers.sequence(0, totalPages - 1)`로 **전체 페이지 번호를 매 요청마다 생성**한다.
- `order/list.html`
- `mypage/reviews.html`
- `admin/orders.html`
- `admin/products.html`
- `wishlist/index.html`

### 왜 최적화 포인트인가?
- 총 페이지 수가 커질수록 링크 DOM 노드가 선형 증가한다.
- 서버 템플릿 렌더링 비용 + HTML 응답 크기 + 브라우저 레이아웃 비용이 함께 증가한다.
- 이미 프로젝트에 윈도우 기반 공용 페이지네이션(`fragments/product-card.html`)이 있으므로, 동일 패턴으로 통일 가능하다.

### 권장 방향
- 각 화면 페이지네이션을 공용 fragment 호출로 치환하거나,
- 현재 화면 중심 `start/end` 범위를 계산하여 `sequence(start, end)`만 생성한다.
- 필요시 `처음/끝` 점프 링크를 보조로 추가해 탐색성을 유지한다.

## 2) 반복 루프 내 `contains` 호출

`product/detail.html` 리뷰 리스트에서 `helpedReviewIds.contains(review.reviewId)`를 버튼 class, 아이콘 class에 각각 호출한다.

### 왜 최적화 포인트인가?
- 리뷰 건수 N, `helpedReviewIds`가 List일 때 `contains`는 평균 O(N) 탐색이어서,
- 반복 루프 안 다중 호출 시 비용이 누적될 수 있다.

### 권장 방향
- 컨트롤러에서 `helpedReviewIds`를 `Set<Long>`으로 전달해 membership check를 O(1)로 낮춘다.
- 템플릿에서는 `th:with="isHelped=${...contains(...)}"`로 1회 계산 후 재사용한다.

## 3) 긴 삼항식/문자열 결합 표현식의 반복 평가

아래 템플릿은 렌더링 시 긴 표현식을 반복 평가한다.
- `order/checkout.html`의 쿠폰 옵션 텍스트(`th:text`) 조합
- `order/list.html`의 주문 상태 뱃지(클래스/문구) 이중 삼항식
- `admin/orders.html`의 상태 필터 텍스트 삼항식

### 왜 최적화 포인트인가?
- EL 파싱/평가 자체가 매우 비싼 연산은 아니지만, 데이터 건수가 증가하면 누적된다.
- 가독성 저하로 유지보수 중 버그 유입 가능성이 커진다(성능 + 품질 측면).

### 권장 방향
- 상태 라벨/스타일 문자열은 서버 DTO에서 미리 계산해 전달한다.
- 쿠폰 표시 문자열도 서버에서 `displayName` 등으로 전처리해 템플릿 연산을 단순화한다.
- 템플릿에서는 단순 바인딩만 수행해 렌더링 일관성 확보.

## 4) 별점 렌더링 반복 블록의 공통 fragment화

`product/detail.html`, `mypage/reviews.html` 등에서 1~5 시퀀스 루프를 각각 정의해 별점을 렌더링한다.

### 왜 최적화 포인트인가?
- 직접적인 성능 병목은 작더라도, 중복 템플릿 블록은 수정/확장 시 비용을 키운다.
- 공통 fragment로 통합하면 중복 제거 + 일관성 확보 + 마크업 크기 감소 효과가 있다.

### 권장 방향
- `fragments/rating.html` 같은 공용 fragment 도입 후 재사용.

---

## 우선순위 제안
1. **높음:** 전체 페이지 순회형 페이지네이션 제거(대상 5개 파일)
2. **중간:** 리뷰 helpful membership check 최적화(Set + `th:with`)
3. **중간:** 긴 표현식 서버 전처리(DTO 필드화)
4. **낮음:** 별점 fragment 공통화(구조 개선)

## 기대 효과
- 페이지 수가 큰 목록 화면에서 응답 HTML 크기/렌더링 비용 감소
- 템플릿 표현식 평가 감소 및 가독성 개선
- 중복 마크업 제거로 변경 비용 감소

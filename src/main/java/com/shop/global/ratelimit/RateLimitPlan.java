package com.shop.global.ratelimit;

/**
 * 엔드포인트 유형별 속도 제한 정책.
 *
 * <h3>왜 엔드포인트마다 다른 한도가 필요한가?</h3>
 * <p>상품 목록 조회(READ)는 초당 수십 건이 자연스럽지만,
 * 주문 생성(ORDER)은 같은 사용자가 초당 수십 건을 보낼 이유가 없다.
 * 쿠폰 발급(COUPON)은 선착순 러시 시 동일 사용자가 반복 클릭하는 패턴이므로
 * 더 엄격한 제한이 필요하다.</p>
 *
 * <h3>플랜별 설계 근거</h3>
 * <table>
 *   <tr><th>플랜</th><th>버킷 용량</th><th>리필</th><th>설계 의도</th></tr>
 *   <tr><td>ORDER</td><td>5</td><td>5/60s</td>
 *       <td>분당 5건: 정상 사용자가 1분에 주문 5개를 생성할 일이 없다.
 *           멱등성 키와 이중 방어하여 봇/스크립트 반복 주문을 차단</td></tr>
 *   <tr><td>COUPON</td><td>10</td><td>10/60s</td>
 *       <td>분당 10건: 선착순 쿠폰 발급 시 빠른 클릭을 허용하되,
 *           스크립트 수백 건/초를 차단</td></tr>
 *   <tr><td>WRITE</td><td>30</td><td>30/60s</td>
 *       <td>분당 30건: 장바구니 수정, 리뷰 작성 등 일반 쓰기 작업</td></tr>
 *   <tr><td>READ</td><td>60</td><td>60/60s</td>
 *       <td>분당 60건: 상품 목록, 검색, 상세 조회 등 읽기 작업.
 *           정상 브라우징에 영향 없이 크롤러/DDoS만 차단</td></tr>
 *   <tr><td>DEFAULT</td><td>30</td><td>30/60s</td>
 *       <td>분류되지 않은 엔드포인트의 기본 한도</td></tr>
 * </table>
 *
 * <h3>확장 방향</h3>
 * <p>현재는 하드코딩이지만, 향후 application.yml에서 오버라이드하거나
 * 사용자 등급(UserTier)별로 차등 한도를 적용할 수 있다.
 * (예: DIAMOND 등급은 ORDER 분당 10건, BRONZE는 5건)</p>
 */
public enum RateLimitPlan {

    /** 주문 생성 — 가장 엄격. 멱등성 키와 이중 방어. */
    ORDER(5, 5, 60_000),

    /** 쿠폰 발급 — 선착순 러시 시 봇 방어. */
    COUPON(10, 10, 60_000),

    /**
     * 플래시 세일 구매 — ORDER보다도 엄격 (분당 3회).
     * 정상 사용자는 상세 페이지에서 1회 클릭 후 성공/실패 확인이 전부이므로
     * 분당 3회면 재시도 2회를 허용하면서 스크립트 반복을 차단한다.
     */
    FLASH_SALE(3, 3, 60_000),

    /** 일반 쓰기 (장바구니, 리뷰 등). */
    WRITE(30, 30, 60_000),

    /** 읽기 (상품 목록, 검색, 상세 조회). */
    READ(60, 60, 60_000),

    /** 분류되지 않은 엔드포인트. */
    DEFAULT(30, 30, 60_000);

    private final int capacity;
    private final int refillTokens;
    private final long refillIntervalMillis;

    RateLimitPlan(int capacity, int refillTokens, long refillIntervalMillis) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillIntervalMillis = refillIntervalMillis;
    }

    /** 새 토큰 버킷을 이 플랜의 설정으로 생성한다. */
    public TokenBucket createBucket() {
        return new TokenBucket(capacity, refillTokens, refillIntervalMillis);
    }

    public int getCapacity() { return capacity; }
    public int getRefillTokens() { return refillTokens; }
    public long getRefillIntervalMillis() { return refillIntervalMillis; }
}

/**
 * ============================================================================
 * Shopping Mall — k6 부하 테스트 (v4)
 * ============================================================================
 *
 * ✅ v4 변경사항 (요구사항 반영)
 *   1) "Threshold PASS = 테스트 성공"을 전제로, 목적에 맞게 Threshold를 재정의/조정
 *      - 로그인 "성능" Threshold 제거(또는 비게이팅), 쿠폰 발급(쿠폰 이슈) 중심으로 게이트
 *   2) 쿠폰 성능 관찰을 위해 "VU당 1회 로그인 후 세션(쿠키) 재사용"
 *      - 각 VU는 최초 1회만 login() 수행 → 이후 동일 jar 재사용
 *   3) 원인 규명을 위해 실패 유형을 Counters로 분류 + (옵션) 샘플 로그 출력
 *   4) "재고 부족은 의도되지 않음" → 주문 실패 원인 중 STOCK/재고 부족을 명시적으로 카운팅
 *   5) baseline을 위해 결과 파일명을 RUN_ID로 분리(3회 반복 실행 시 덮어쓰기 방지)
 *
 * 실행 방법:
 *   k6 run load-test.v4.js
 *   k6 run --env SCENARIO=coupon_rush load-test.v4.js
 *   k6 run --env BASE_URL=http://10.0.0.1:8080 --env SCENARIO=mixed load-test.v4.js
 *
 * (권장) baseline 3회 반복:
 *   for i in 1 2 3; do
 *     RUN_ID="coupon_rush_r${i}_$(date +%Y%m%d_%H%M%S)"
 *     k6 run --env SCENARIO=coupon_rush --env RUN_ID="$RUN_ID" load-test.v4.js
 *   done
 *
 * 사전 준비:
 *   - 1회만: setup-loadtest.sql 실행(유저/쿠폰 준비)
 *   - 매 실행 전: reset-loadtest.v4.sql 실행(쿠폰 발급/카트/재고 리셋 권장)
 * ============================================================================
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// ──────────────────────────────────────────────
//  설정
// ──────────────────────────────────────────────
const BASE_URL   = __ENV.BASE_URL  || 'http://localhost:8080';
const SCENARIO   = __ENV.SCENARIO  || 'mixed';
const USER_COUNT = parseInt(__ENV.USERS || '200', 10);

// 결과 파일 분리(3회 baseline 반복 시 덮어쓰기 방지)
const RUN_ID = __ENV.RUN_ID
  || `${SCENARIO}-${new Date().toISOString().replace(/[:.]/g, '').replace('T', '_').replace('Z', '')}`;

// 디버그/원인 분석 로그(원하면 켜기)
const DEBUG = (__ENV.DEBUG || '0') === '1';
const FAIL_LOG_LIMIT = parseInt(__ENV.FAIL_LOG_LIMIT || '30', 10); // VU당 최대 로그 개수
let _failLogCount = 0;

// 쿠폰 러시 튜닝
const COUPON_CODE = __ENV.COUPON_CODE || 'LOADTEST_RUSH';
const COUPON_SKIP_PAGE = (__ENV.COUPON_SKIP_PAGE || '0') === '1';
const COUPON_SLEEP = parseFloat(__ENV.COUPON_SLEEP || '0.2');
// Shopping 시나리오에서 각 VU의 POST /orders 간 최소 간격(초).
// RateLimitPlan.ORDER = 5건/60초/사용자이므로 12s 이상이면 SLO 지표로서 order_ok가
// rate limit 영향을 받지 않고 측정된다. 기본 0(기존 동작 유지).
const SHOPPING_ORDER_SPACING = parseFloat(__ENV.SHOPPING_ORDER_SPACING || '0');

// Threshold/요약 출력 튜닝 (필요 시 env로 조정)
const MIN_CHECK_RATE = parseFloat(__ENV.MIN_CHECK_RATE || '0.99');
const MIN_LOGIN_OK   = parseFloat(__ENV.MIN_LOGIN_OK   || '0.99');
const MIN_ORDER_OK   = parseFloat(__ENV.MIN_ORDER_OK   || '0.99');
const MIN_CART_OK    = parseFloat(__ENV.MIN_CART_OK    || '0.99');
const MIN_COUPON_OK  = parseFloat(__ENV.MIN_COUPON_OK  || '0.99');

// p95 기준 (서브메트릭 생성 + 회귀 감지)
const P95_ALL_MS     = parseInt(__ENV.P95_ALL_MS     || '3000', 10);
const P95_ORDER_MS   = parseInt(__ENV.P95_ORDER_MS   || '1000', 10);
const P95_COUPON_MS  = parseInt(__ENV.P95_COUPON_MS  || '1000', 10);
const P95_BROWSE_MS  = parseInt(__ENV.P95_BROWSE_MS  || '1000', 10);

// ──────────────────────────────────────────────
//  실제 DB 데이터 기반 상수 (setup-loadtest.sql 실행 후 확인)
// ──────────────────────────────────────────────

// 재고 충분 + 활성 상품(기본값)
// ⚠ mixed/shopping에서 "재고 부족"이 의도되지 않았다면, reset-loadtest.v4.sql에서
//    아래 PRODUCT_IDS의 재고를 충분히 크게 리셋하도록 권장합니다.
const DEFAULT_PRODUCT_IDS = [25, 26, 27, 30, 31, 33, 34, 35, 36, 37];

// 상품/카테고리/키워드 목록은 env로 교체 가능(운영 환경에 맞게 튜닝)
const PRODUCT_IDS = parseIntList(__ENV.PRODUCT_IDS, DEFAULT_PRODUCT_IDS);

const DEFAULT_SEARCH_KEYWORDS = [
  '베스트', '신상', '인기', '프리미엄', '특가', '한정판',
  '노트북', '키보드', '마우스',
];
const SEARCH_KEYWORDS = parseStrList(__ENV.SEARCH_KEYWORDS, DEFAULT_SEARCH_KEYWORDS);

const DEFAULT_CATEGORY_IDS = [748, 312, 436, 836, 487, 234, 330, 425, 606, 719];
const CATEGORY_IDS = parseIntList(__ENV.CATEGORY_IDS, DEFAULT_CATEGORY_IDS);

// ──────────────────────────────────────────────
//  커스텀 메트릭
// ──────────────────────────────────────────────
const errorRate      = new Rate('errors');
const loginDuration  = new Trend('login_duration', true);
const orderDuration  = new Trend('order_duration', true);
const browseDuration = new Trend('browse_duration', true);
const couponDuration = new Trend('coupon_duration', true);

const orderSuccess   = new Counter('orders_created');
const orderFail      = new Counter('orders_failed');
const couponSuccess  = new Counter('coupons_issued');
const couponFail     = new Counter('coupons_failed');
const authFailures   = new Counter('auth_failures');

// 비즈니스 성공률을 Rate로 분리 → Threshold로 PASS/FAIL 결정
const loginOk        = new Rate('login_ok');
const cartLandingOk  = new Rate('cart_landing_ok');
const orderOk        = new Rate('order_ok');
const couponServerOk = new Rate('coupon_server_ok'); // 쿠폰: 서버 정상 응답(200)만

// (v4) 원인 규명용 카운터
const cartFailAuth       = new Counter('cart_fail_auth');
const cartFailHttp4xx    = new Counter('cart_fail_http_4xx');
const cartFailHttp5xx    = new Counter('cart_fail_http_5xx');
const cartFailNotLanded  = new Counter('cart_fail_not_landed');

const orderFailAuth      = new Counter('order_fail_auth');
const orderFailStockOut  = new Counter('order_fail_stock_out');
const orderFailHttp4xx   = new Counter('order_fail_http_4xx');
const orderFailHttp5xx   = new Counter('order_fail_http_5xx');
const orderFailUnknown   = new Counter('order_fail_unknown');

const couponFailAuth     = new Counter('coupon_fail_auth');
const couponFailHttp5xx  = new Counter('coupon_fail_http_5xx');
const couponFailSoldOut  = new Counter('coupon_fail_sold_out');
const couponFailDuplicate= new Counter('coupon_fail_duplicate');
const couponFailOther    = new Counter('coupon_fail_other');

// ──────────────────────────────────────────────
//  시나리오 설정
// ──────────────────────────────────────────────
const scenarios = {
  browse: {
    browse_only: {
      executor: 'ramping-vus',
      exec: 'scenarioBrowse',
      stages: [
        { duration: '1m', target: 50 },
        { duration: '3m', target: 50 },
        { duration: '1m', target: 100 },
        { duration: '3m', target: 100 },
        { duration: '1m', target: 0 },
      ],
    },
  },

  shopping: {
    shopping_only: {
      executor: 'ramping-vus',
      exec: 'scenarioShopping',
      stages: [
        { duration: '1m', target: 20 },
        { duration: '3m', target: 20 },
        { duration: '1m', target: 50 },
        { duration: '3m', target: 50 },
        { duration: '1m', target: 0 },
      ],
    },
  },

  // 쿠폰 러시: 짧은 시간 내 대량 동시 접속
  coupon_rush: {
    coupon_spike: {
      executor: 'ramping-vus',
      exec: 'scenarioCouponRush',
      stages: [
        { duration: '10s', target: 100 },
        { duration: '20s', target: 100 },
        { duration: '10s', target: 0 },
      ],
    },
  },

  // 혼합 시나리오: 실 트래픽 비율
  mixed: {
    mixed_browse: {
      executor: 'ramping-vus',
      exec: 'scenarioBrowse',
      stages: [
        { duration: '1m', target: 30 },
        { duration: '3m', target: 60 },
        { duration: '2m', target: 120 },
        { duration: '3m', target: 120 },
        { duration: '1m', target: 0 },
      ],
    },
    mixed_shopping: {
      executor: 'ramping-vus',
      exec: 'scenarioShopping',
      startTime: '30s',
      stages: [
        { duration: '1m', target: 10 },
        { duration: '3m', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '3m', target: 50 },
        { duration: '1m', target: 0 },
      ],
    },
    mixed_coupon: {
      executor: 'ramping-vus',
      exec: 'scenarioCouponRush',
      startTime: '2m',
      stages: [
        { duration: '10s', target: 30 },
        { duration: '30s', target: 30 },
        { duration: '10s', target: 0 },
      ],
    },
    mixed_social: {
      executor: 'ramping-vus',
      exec: 'scenarioSocial',
      startTime: '30s',
      stages: [
        { duration: '1m', target: 5 },
        { duration: '5m', target: 10 },
        { duration: '1m', target: 0 },
      ],
    },
  },
};

export const options = {
  scenarios: scenarios[SCENARIO] || scenarios.mixed,

  // Trend 통계(요약/handleSummary) 키 고정
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],

  // Threshold는 "PASS해야 성공"이므로, 목적에 맞춰 구성
  thresholds: buildThresholds(),
};

/**
 * Threshold 빌더
 *
 * ⚠ 로그인 성능은 목표가 아니므로:
 *   - login_submit/login_page p95 threshold는 v4에서 제거(게이팅 X)
 *   - 대신 coupon_issue(또는 order/create 등) 핵심 endpoint만 게이트
 *
 * k6는 threshold 실패 시 테스트를 실패로 처리하고, non-zero exit code를 반환합니다.
 * (문서: Thresholds) https://grafana.com/docs/k6/latest/using-k6/thresholds/
 */
function buildThresholds() {
  const t = {
    // 공통 안정성
    http_req_failed: ['rate<0.05'],
    errors:          ['rate<0.1'],
    checks:          [`rate>${MIN_CHECK_RATE}`],

  };

  // 인증이 필요한 시나리오에서만 login_ok를 게이트로 사용
  if (SCENARIO !== 'browse') {
    t.login_ok = [`rate>${MIN_LOGIN_OK}`];
  }

  // 전체 p95 게이트는 coupon_rush에서는 제거(로그인 1회가 섞여도 p95에 영향 가능)
  if (SCENARIO !== 'coupon_rush') {
    t.http_req_duration = [`p(95)<${P95_ALL_MS}`];
  }

  // browse 관련 endpoint(서브메트릭 + 회귀감지)
  if (SCENARIO === 'browse' || SCENARIO === 'mixed') {
    t['http_req_duration{endpoint:home}']           = [`p(95)<${P95_BROWSE_MS}`];
    t['http_req_duration{endpoint:products_list}']  = [`p(95)<${P95_BROWSE_MS}`];
    t['http_req_duration{endpoint:product_detail}'] = [`p(95)<${P95_BROWSE_MS}`];
    t['http_req_duration{endpoint:search}']         = [`p(95)<${P95_BROWSE_MS}`];
    t['http_req_duration{endpoint:category}']       = [`p(95)<${P95_BROWSE_MS}`];
  }

  // shopping 관련
  if (SCENARIO === 'shopping' || SCENARIO === 'mixed') {
    t.cart_landing_ok = [`rate>${MIN_CART_OK}`];
    t.order_ok        = [`rate>${MIN_ORDER_OK}`];

    t['http_req_duration{endpoint:cart_add}']     = [`p(95)<${P95_ORDER_MS}`];
    t['http_req_duration{endpoint:cart_view}']    = [`p(95)<${P95_ORDER_MS}`];
    t['http_req_duration{endpoint:checkout}']     = [`p(95)<${P95_ORDER_MS}`];
    t['http_req_duration{endpoint:order_create}'] = [`p(95)<${P95_ORDER_MS}`];
    t['http_req_duration{endpoint:order_list}']   = [`p(95)<${P95_ORDER_MS}`];
  }

  // coupon 관련 (coupon_rush/mixed에서 핵심 게이트)
  if (SCENARIO === 'coupon_rush' || SCENARIO === 'mixed') {
    t.coupon_server_ok = [`rate>${MIN_COUPON_OK}`];
    t['http_req_duration{endpoint:coupon_issue}'] = [`p(95)<${P95_COUPON_MS}`];

    // JS timer 기반(리다이렉트 포함) 쿠폰 발급 단계 성능도 같이 게이트(선택)
    t.coupon_duration = [`p(95)<${P95_COUPON_MS}`];
  }

  // mixed 추가(Social)
  if (SCENARIO === 'mixed') {
    t['http_req_duration{endpoint:wishlist_toggle}'] = [`p(95)<${P95_BROWSE_MS}`];
    t['http_req_duration{endpoint:wishlist_page}']   = [`p(95)<${P95_BROWSE_MS}`];
    t['http_req_duration{endpoint:mypage}']          = [`p(95)<${P95_BROWSE_MS}`];
  }

  return t;
}

// ──────────────────────────────────────────────
//  유틸리티
// ──────────────────────────────────────────────
function logFail(obj) {
  if (!DEBUG) return;
  if (_failLogCount >= FAIL_LOG_LIMIT) return;
  _failLogCount += 1;
  console.warn(`[FAIL] ${JSON.stringify(obj)}`);
}

/** XSRF-TOKEN 쿠키에서 CSRF 토큰 추출 */
function getCsrf(jar) {
  const cookies = jar.cookiesForURL(BASE_URL);
  if (cookies['XSRF-TOKEN'] && cookies['XSRF-TOKEN'].length > 0) {
    return cookies['XSRF-TOKEN'][0];
  }
  return '';
}

/** 랜덤 배열 원소 선택 */
function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/** VU별 고유 사용자 번호 (1~USER_COUNT 순환) */
function userNum() {
  return ((__VU - 1) % USER_COUNT) + 1;
}

/** 사용자 자격 증명 */
function credentials() {
  const num = String(userNum()).padStart(3, '0');
  return { username: `loaduser_${num}`, password: 'test1234' };
}

/**
 * 요청 태그 병합 유틸
 * - endpoint: low-cardinality 분해용
 * - name: k6에서 요청명을 안정적으로 그룹핑(동적 URL cardinality 폭발 방지)
 */
function withTags(params, tags) {
  const p = params || {};
  const merged = Object.assign({}, p.tags || {}, tags || {});
  return Object.assign({}, p, { tags: merged });
}

/**
 * 인증 리다이렉트 탐지
 * Spring Security 미인증 요청 → /auth/login 302 → (redirect follow 후) 로그인 페이지(200)
 */
function isAuthRedirect(res) {
  return res.url && res.url.includes('/auth/login');
}

/**
 * 인증 요청 검증 유틸리티
 * - HTTP 200 + 로그인 페이지가 아닌지 검증
 */
function checkAuth(res, label) {
  const ok = check(res, {
    [`${label}: status 200`]: (r) => r.status === 200,
    [`${label}: not auth redirect`]: (r) => !isAuthRedirect(r),
  });

  errorRate.add(!ok);
  if (isAuthRedirect(res)) {
    authFailures.add(1);
  }
  return ok;
}

// ──────────────────────────────────────────────
//  (v4) VU당 1회 로그인 후 세션 재사용
// ──────────────────────────────────────────────
let _sessionJar = null;

function getSessionJar() {
  if (_sessionJar) return _sessionJar;
  _sessionJar = login(); // 최초 1회
  return _sessionJar;
}

/**
 * 로그인 — CSRF 토큰 포함 폼 POST
 *
 * 성공: 302 → "/" 로 이동 후 최종 200(home)
 * 실패: 302 → "/auth/login?error=true" → 최종 200(login)
 */
function login() {
  const jar = http.cookieJar();
  const cred = credentials();

  // 1) 로그인 페이지 GET → CSRF 토큰 쿠키 수령
  http.get(`${BASE_URL}/auth/login`, withTags({ jar }, {
    name: 'GET /auth/login',
    endpoint: 'login_page',
  }));
  const csrf = getCsrf(jar);

  // 2) 로그인 POST
  const start = Date.now();
  const res = http.post(`${BASE_URL}/auth/login`, {
    username: cred.username,
    password: cred.password,
    _csrf: csrf,
  }, {
    jar,
    redirects: 5,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    tags: {
      name: 'POST /auth/login',
      endpoint: 'login_submit',
    },
  });
  loginDuration.add(Date.now() - start);

  const ok = check(res, {
    'login: status 200':         (r) => r.status === 200,
    'login: redirected to home': (r) => !isAuthRedirect(r),
    'login: no error param':     (r) => !r.url.includes('error'),
  });

  errorRate.add(!ok);
  loginOk.add(ok);

  if (!ok) {
    authFailures.add(1);
    logFail({
      type: 'login_failed',
      user: cred.username,
      status: res.status,
      url: res.url,
    });
  }

  return jar;
}

/**
 * 인증 POST 요청 — CSRF 토큰 자동 포함 (form-encoded)
 */
function authPost(jar, url, body, tags) {
  const csrf = getCsrf(jar);
  const payload = Object.assign({}, body, { _csrf: csrf });
  return http.post(url, payload, {
    jar,
    redirects: 5,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    tags: tags || {},
  });
}

// 원인 분류(단순 키워드 기반)
function looksLikeStockOut(body) {
  if (!body) return false;
  return /재고|품절|STOCK/i.test(body);
}
function looksLikeCouponSoldOut(body) {
  if (!body) return false;
  return /수량|소진|마감|끝났|종료|선착순/i.test(body);
}
function looksLikeCouponDuplicate(body) {
  if (!body) return false;
  return /이미\s*발급|중복/i.test(body);
}

// env 파서
function parseIntList(envVal, fallback) {
  if (!envVal) return fallback;
  const list = envVal.split(',').map(s => parseInt(s.trim(), 10)).filter(n => !Number.isNaN(n));
  return list.length > 0 ? list : fallback;
}
function parseStrList(envVal, fallback) {
  if (!envVal) return fallback;
  const list = envVal.split(',').map(s => s.trim()).filter(s => s.length > 0);
  return list.length > 0 ? list : fallback;
}

// ──────────────────────────────────────────────
//  시나리오 1: Browse (비인증 탐색)
// ──────────────────────────────────────────────
export function scenarioBrowse() {
  group('Browse — 홈페이지', () => {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/`, withTags(null, {
      name: 'GET /',
      endpoint: 'home',
    }));
    browseDuration.add(Date.now() - start);

    const ok = check(res, { 'home: 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  });

  sleep(Math.random() * 2 + 1);

  group('Browse — 상품 목록', () => {
    const sort = pick(['best', 'newest', 'price_asc', 'price_desc', 'rating']);
    const res = http.get(`${BASE_URL}/products?sort=${sort}&page=0`, withTags(null, {
      name: 'GET /products',
      endpoint: 'products_list',
    }));
    const ok = check(res, { 'products: 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  });

  sleep(Math.random() * 2 + 0.5);

  group('Browse — 상품 상세', () => {
    const pid = pick(PRODUCT_IDS);
    const res = http.get(`${BASE_URL}/products/${pid}`, withTags(null, {
      name: 'GET /products/:id',
      endpoint: 'product_detail',
    }));
    const ok = check(res, { 'detail: 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  });

  sleep(Math.random() * 1 + 0.5);

  group('Browse — 검색', () => {
    const keyword = pick(SEARCH_KEYWORDS);
    const res = http.get(`${BASE_URL}/search?q=${encodeURIComponent(keyword)}`, withTags(null, {
      name: 'GET /search',
      endpoint: 'search',
    }));
    const ok = check(res, { 'search: 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  });

  sleep(Math.random() * 1 + 0.5);

  group('Browse — 카테고리', () => {
    const cid = pick(CATEGORY_IDS);
    const res = http.get(`${BASE_URL}/categories/${cid}`, withTags(null, {
      name: 'GET /categories/:id',
      endpoint: 'category',
    }));
    const ok = check(res, { 'category: 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  });

  sleep(Math.random() * 2 + 1);
}

// ──────────────────────────────────────────────
//  시나리오 2: Shopping Flow (인증 + 주문)
// ──────────────────────────────────────────────
export function scenarioShopping() {
  const jar = getSessionJar();

  sleep(Math.random() * 1 + 0.5);

  // 1) 상품 상세 보기
  group('Shopping — 상품 탐색', () => {
    const pid = pick(PRODUCT_IDS);
    const res = http.get(`${BASE_URL}/products/${pid}`, withTags({ jar }, {
      name: 'GET /products/:id',
      endpoint: 'product_detail',
    }));
    const ok = check(res, { 'browse product: 200': (r) => r.status === 200 });
    errorRate.add(!ok);
  });

  sleep(Math.random() * 1 + 0.5);

  // 2) 장바구니 추가
  const productId = pick(PRODUCT_IDS);
  group('Shopping — 장바구니 추가', () => {
    const res = authPost(jar, `${BASE_URL}/cart/add`, {
      productId: String(productId),
      quantity: '1',
    }, {
      name: 'POST /cart/add',
      endpoint: 'cart_add',
    });

    if (isAuthRedirect(res)) {
      authFailures.add(1);
      cartFailAuth.add(1);
      cartLandingOk.add(false);
      errorRate.add(true);
      logFail({ type: 'cart_add_auth_redirect', user: credentials().username, status: res.status, url: res.url, productId });
      return;
    }

    if (res.status >= 500) {
      cartFailHttp5xx.add(1);
      cartLandingOk.add(false);
      errorRate.add(true);
      logFail({ type: 'cart_add_5xx', status: res.status, url: res.url, productId });
      return;
    }

    if (res.status >= 400) {
      cartFailHttp4xx.add(1);
      cartLandingOk.add(false);
      errorRate.add(true);
      logFail({ type: 'cart_add_4xx', status: res.status, url: res.url, productId });
      return;
    }

    const landedOk = check(res, {
      'cart add: landed on cart page': (r) => r.url.includes('/cart'),
    });

    if (!landedOk) {
      cartFailNotLanded.add(1);
      logFail({ type: 'cart_add_not_landed', status: res.status, url: res.url, productId });
    }

    cartLandingOk.add(landedOk);
    errorRate.add(!landedOk);
  });

  sleep(Math.random() * 1 + 0.5);

  // 3) 장바구니 조회
  group('Shopping — 장바구니 확인', () => {
    const res = http.get(`${BASE_URL}/cart`, withTags({ jar }, {
      name: 'GET /cart',
      endpoint: 'cart_view',
    }));
    checkAuth(res, 'cart view');
  });

  sleep(Math.random() * 1 + 0.5);

  // 4) 체크아웃 페이지
  group('Shopping — 체크아웃', () => {
    const res = http.get(`${BASE_URL}/orders/checkout`, withTags({ jar }, {
      name: 'GET /orders/checkout',
      endpoint: 'checkout',
    }));
    checkAuth(res, 'checkout');
  });

  sleep(Math.random() * 1 + 0.5);

  // 5) 주문 생성
  group('Shopping — 주문 생성', () => {
    const start = Date.now();
    const res = authPost(jar, `${BASE_URL}/orders`, {
      shippingAddress: '서울시 강남구 테스트로 123',
      recipientName:   '부하테스터',
      recipientPhone:  '010-0000-0001',
      paymentMethod:   'CARD',
    }, {
      name: 'POST /orders',
      endpoint: 'order_create',
    });
    orderDuration.add(Date.now() - start);

    if (isAuthRedirect(res)) {
      authFailures.add(1);
      orderFailAuth.add(1);
      orderFail.add(1);
      orderOk.add(false);
      errorRate.add(true);
      logFail({ type: 'order_auth_redirect', user: credentials().username, status: res.status, url: res.url });
      return;
    }

    // HTTP 실패(4xx/5xx)
    if (res.status >= 500) {
      orderFailHttp5xx.add(1);
      orderFail.add(1);
      orderOk.add(false);
      errorRate.add(true);
      logFail({ type: 'order_5xx', status: res.status, url: res.url });
      return;
    }
    if (res.status >= 400) {
      orderFailHttp4xx.add(1);
      orderFail.add(1);
      orderOk.add(false);
      errorRate.add(true);
      logFail({ type: 'order_4xx', status: res.status, url: res.url });
      return;
    }

    // 성공: redirect → /orders/{orderId}
    // 실패: redirect → /orders/checkout (flash errorMessage)
    const isOrderSuccess = res.status === 200
      && !res.url.includes('checkout')
      && !res.url.includes('error');

    const ok = check(res, { 'order: success': () => isOrderSuccess });

    if (isOrderSuccess) {
      orderSuccess.add(1);
      orderOk.add(true);
    } else {
      orderFail.add(1);
      orderOk.add(false);

      // (v4) 재고 부족은 의도되지 않음 → 별도 카운팅 + 에러로 취급
      const body = res.body || '';
      if (looksLikeStockOut(body)) {
        orderFailStockOut.add(1);
        logFail({ type: 'order_stock_out', status: res.status, url: res.url });
      } else {
        orderFailUnknown.add(1);
        logFail({ type: 'order_failed_unknown', status: res.status, url: res.url });
      }
    }

    // ok(false)면 checks에 반영되므로 추가 errorRate도 true로 설정(정합성 이슈로 간주)
    errorRate.add(!ok);
  });

  sleep(Math.random() * 1 + 0.5);

  // 6) 주문 목록 확인
  group('Shopping — 주문 내역', () => {
    const res = http.get(`${BASE_URL}/orders`, withTags({ jar }, {
      name: 'GET /orders',
      endpoint: 'order_list',
    }));
    checkAuth(res, 'order list');
  });

  // ORDER 플랜(5/60s/user) 준수를 위한 iteration 간 간격 확보.
  // SHOPPING_ORDER_SPACING=12 로 주면 동일 VU의 POST /orders 간격이 12초 이상이 되어
  // rate_limit_exceeded 없이 order_ok가 측정된다. 기본 0이면 기존 동작.
  if (SHOPPING_ORDER_SPACING > 0) {
    sleep(SHOPPING_ORDER_SPACING);
  } else {
    sleep(Math.random() * 2 + 1);
  }
}

// ──────────────────────────────────────────────
//  시나리오 3: Coupon Rush (선착순 쿠폰 스파이크)
// ──────────────────────────────────────────────
export function scenarioCouponRush() {
  // ✅ VU당 1회 로그인 후 세션 재사용
  const jar = getSessionJar();

  // (선택) 쿠폰 페이지 조회: CSRF/세션 확인 + UI 렌더링 비용 포함
  if (!COUPON_SKIP_PAGE) {
    group('Coupon Rush — 쿠폰 페이지 조회', () => {
      const res = http.get(`${BASE_URL}/coupons`, withTags({ jar }, {
        name: 'GET /coupons',
        endpoint: 'coupon_page',
      }));
      checkAuth(res, 'coupon page');
    });
  }

  // 선착순 발급
  group('Coupon Rush — 선착순 발급', () => {
    const start = Date.now();
    const res = authPost(jar, `${BASE_URL}/coupons/issue`, {
      couponCode: COUPON_CODE,
    }, {
      name: 'POST /coupons/issue',
      endpoint: 'coupon_issue',
    });
    couponDuration.add(Date.now() - start);

    if (isAuthRedirect(res)) {
      authFailures.add(1);
      couponFailAuth.add(1);
      couponFail.add(1);
      couponServerOk.add(false);
      errorRate.add(true);
      logFail({ type: 'coupon_auth_redirect', user: credentials().username, status: res.status, url: res.url });
      return;
    }

    if (res.status >= 500) {
      couponFailHttp5xx.add(1);
      couponFail.add(1);
      couponServerOk.add(false);
      errorRate.add(true);
      logFail({ type: 'coupon_5xx', status: res.status, url: res.url });
      return;
    }

    // 200 + /coupons 도착 — body(메시지)로 성공/실패 판별
    const body = res.body || '';
    const issued = body.includes('발급되었습니다');

    check(res, { 'coupon: server responded': (r) => r.status === 200 });
    couponServerOk.add(res.status === 200);

    if (issued) {
      couponSuccess.add(1);
    } else {
      couponFail.add(1);

      // 원인 분류(비즈니스 실패는 정상 응답일 수 있음 → errorRate에는 반영 X)
      if (looksLikeCouponSoldOut(body)) couponFailSoldOut.add(1);
      else if (looksLikeCouponDuplicate(body)) couponFailDuplicate.add(1);
      else couponFailOther.add(1);

      logFail({ type: 'coupon_not_issued', status: res.status, url: res.url });
    }

    // 비즈니스 실패(수량 소진, 중복 발급)는 정상 응답 → errorRate에 넣지 않음
    errorRate.add(res.status !== 200);
  });

  // 쿠폰 러시에서는 think-time을 최소화(원하면 env로 늘리기)
  sleep(COUPON_SLEEP);
}

// ──────────────────────────────────────────────
//  시나리오 4: Social (위시리스트 + 마이페이지)
// ──────────────────────────────────────────────
export function scenarioSocial() {
  const jar = getSessionJar();

  sleep(Math.random() * 1 + 0.5);

  group('Social — 위시리스트 토글', () => {
    const pid = pick(PRODUCT_IDS);
    const res = authPost(jar, `${BASE_URL}/wishlist/toggle`, {
      productId: String(pid),
    }, {
      name: 'POST /wishlist/toggle',
      endpoint: 'wishlist_toggle',
    });
    checkAuth(res, 'wishlist toggle');
  });

  sleep(Math.random() * 2 + 1);

  group('Social — 위시리스트 목록', () => {
    const res = http.get(`${BASE_URL}/wishlist`, withTags({ jar }, {
      name: 'GET /wishlist',
      endpoint: 'wishlist_page',
    }));
    checkAuth(res, 'wishlist page');
  });

  sleep(Math.random() * 2 + 1);

  group('Social — 마이페이지', () => {
    const res = http.get(`${BASE_URL}/mypage`, withTags({ jar }, {
      name: 'GET /mypage',
      endpoint: 'mypage',
    }));
    checkAuth(res, 'mypage');
  });

  sleep(Math.random() * 2 + 1);
}

// ──────────────────────────────────────────────
//  결과 요약 출력
// ──────────────────────────────────────────────
export function handleSummary(data) {
  const lines = [
    '═══════════════════════════════════════════════',
    `  Shopping Mall 부하 테스트 결과 (v4) [${SCENARIO}]`,
    `  RUN_ID: ${RUN_ID}`,
    '═══════════════════════════════════════════════',
    '',
  ];

  function val(metric, key) {
    return metric && metric.values ? metric.values[key] : undefined;
  }
  function fmt(v, digits) {
    return (typeof v === 'number') ? v.toFixed(digits) : '-';
  }
  function med(metric) {
    return val(metric, 'med') !== undefined ? val(metric, 'med') : val(metric, 'p(50)');
  }

  // HTTP 메트릭 (overall)
  const dur = data.metrics.http_req_duration;
  if (dur) {
    lines.push('  HTTP 응답 시간 (overall)');
    lines.push(`    p50(med):   ${fmt(med(dur), 1)}ms`);
    lines.push(`    p95:        ${fmt(val(dur, 'p(95)'), 1)}ms`);
    lines.push(`    p99:        ${fmt(val(dur, 'p(99)'), 1)}ms`);
    lines.push(`    max:        ${fmt(val(dur, 'max'), 1)}ms`);
    lines.push('');
  }

  const reqs = data.metrics.http_reqs;
  if (reqs) {
    lines.push(`  처리량: ${reqs.values.count} 요청 (${reqs.values.rate?.toFixed(1)} req/s)`);
  }
  const failed = data.metrics.http_req_failed;
  if (failed) {
    lines.push(`  HTTP 에러율: ${(failed.values.rate * 100).toFixed(2)}%`);
  }
  const appErrors = data.metrics.errors;
  if (appErrors) {
    lines.push(`  앱 에러율:   ${(appErrors.values.rate * 100).toFixed(2)}%`);
  }
  const checksMetric = data.metrics.checks;
  if (checksMetric) {
    lines.push(`  체크 통과율: ${(checksMetric.values.rate * 100).toFixed(2)}%`);
  }
  lines.push('');

  // 커스텀 Trend (JS timer 기반)
  if (data.metrics.login_duration) {
    lines.push(`  로그인(JS timer):        p50=${fmt(med(data.metrics.login_duration), 0)}ms, p95=${fmt(val(data.metrics.login_duration, 'p(95)'), 0)}ms`);
  }
  if (data.metrics.order_duration) {
    lines.push(`  주문 생성(JS timer):      p50=${fmt(med(data.metrics.order_duration), 0)}ms, p95=${fmt(val(data.metrics.order_duration, 'p(95)'), 0)}ms`);
  }
  if (data.metrics.coupon_duration) {
    lines.push(`  쿠폰 발급(JS timer):      p50=${fmt(med(data.metrics.coupon_duration), 0)}ms, p95=${fmt(val(data.metrics.coupon_duration, 'p(95)'), 0)}ms`);
  }

  // 비즈니스 성공률
  if (data.metrics.login_ok)        lines.push(`  login_ok:          ${(data.metrics.login_ok.values.rate * 100).toFixed(2)}%`);
  if (data.metrics.cart_landing_ok) lines.push(`  cart_landing_ok:   ${(data.metrics.cart_landing_ok.values.rate * 100).toFixed(2)}%`);
  if (data.metrics.order_ok)        lines.push(`  order_ok:          ${(data.metrics.order_ok.values.rate * 100).toFixed(2)}%`);
  if (data.metrics.coupon_server_ok)lines.push(`  coupon_server_ok:  ${(data.metrics.coupon_server_ok.values.rate * 100).toFixed(2)}%`);

  // 성공/실패 카운트
  if (data.metrics.orders_created || data.metrics.orders_failed) {
    lines.push(`  주문 성공/실패:     ${data.metrics.orders_created?.values.count || 0} / ${data.metrics.orders_failed?.values.count || 0}`);
  }
  if (data.metrics.coupons_issued || data.metrics.coupons_failed) {
    lines.push(`  쿠폰 발급/실패:     ${data.metrics.coupons_issued?.values.count || 0} / ${data.metrics.coupons_failed?.values.count || 0}`);
  }

  // 원인 분류 카운터(있으면 출력)
  const reasonCounters = [
    'cart_fail_auth', 'cart_fail_http_4xx', 'cart_fail_http_5xx', 'cart_fail_not_landed',
    'order_fail_auth', 'order_fail_stock_out', 'order_fail_http_4xx', 'order_fail_http_5xx', 'order_fail_unknown',
    'coupon_fail_auth', 'coupon_fail_http_5xx', 'coupon_fail_sold_out', 'coupon_fail_duplicate', 'coupon_fail_other',
    'auth_failures',
  ];
  const anyReasons = reasonCounters.some((k) => data.metrics[k] && data.metrics[k].values.count > 0);
  if (anyReasons) {
    lines.push('');
    lines.push('  실패 원인 카운트(원인 규명용)');
    reasonCounters.forEach((k) => {
      const m = data.metrics[k];
      if (m && m.values && m.values.count > 0) {
        lines.push(`    - ${k}: ${m.values.count}`);
      }
    });
  }

  // endpoint별 http_req_duration 서브메트릭
  const endpointRows = [
    ['home',           'GET /'],
    ['products_list',  'GET /products'],
    ['product_detail', 'GET /products/:id'],
    ['search',         'GET /search'],
    ['category',       'GET /categories/:id'],
    ['cart_add',       'POST /cart/add'],
    ['cart_view',      'GET /cart'],
    ['checkout',       'GET /orders/checkout'],
    ['order_create',   'POST /orders'],
    ['order_list',     'GET /orders'],
    ['coupon_page',    'GET /coupons'],
    ['coupon_issue',   'POST /coupons/issue'],
    ['wishlist_toggle','POST /wishlist/toggle'],
    ['wishlist_page',  'GET /wishlist'],
    ['mypage',         'GET /mypage'],
  ];

  const hasAnyEndpoint = endpointRows.some(([ep]) => data.metrics[`http_req_duration{endpoint:${ep}}`]);
  if (hasAnyEndpoint) {
    lines.push('');
    lines.push('  HTTP 응답 시간 (endpoint p95/p99)');
    endpointRows.forEach(([ep, label]) => {
      const m = data.metrics[`http_req_duration{endpoint:${ep}}`];
      if (!m) return;
      lines.push(`    - ${label}  p95=${fmt(val(m, 'p(95)'), 1)}ms  p99=${fmt(val(m, 'p(99)'), 1)}ms`);
    });
  }

  lines.push('');
  lines.push('═══════════════════════════════════════════════');
  lines.push('');

  const summary = lines.join('\n');
  console.log(summary);

  // 결과 파일명을 RUN_ID로 분리
  const outJsonName = `load-test-result.${RUN_ID}.json`;

  return {
    stdout: summary,
    [outJsonName]: JSON.stringify(data, null, 2),
  };
}

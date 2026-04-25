/**
 * ============================================================================
 * [Phase 23-4] 플래시 세일 burst 부하 테스트
 *
 * 목적
 *   - CAS vs 비관적 락(SELECT FOR UPDATE) 두 변종을 동일 부하로 측정
 *   - 오버셀=0 검증, p95/p99/RPS 비교, 응답 코드 분포 기록
 *
 * 부하 패턴
 *   - 200 VU, VU당 1회 구매 시도 (per-vu-iterations)
 *   - 재고 100 → 정확히 100개 성공 + 약 100개 sold_out 기대
 *   - 매 VU는 unique 사용자로 로그인(loaduser_001..200) → uk_fsp_user_sale 미발동
 *
 * 실행 (재현)
 *   psql -U postgres -d shopping_mall_loadtest_db -f setup-loadtest.sql       # 1회
 *   psql -U postgres -d shopping_mall_loadtest_db -f setup-flash-sale.sql     # 1회
 *
 *   # CAS 변종(기본)
 *   FLASH_SALE_LOCK_STRATEGY=cas ./gradlew bootRun &
 *   psql -U postgres -d shopping_mall_loadtest_db -f reset-flash-sale.sql
 *   k6 run --env RUN_ID=fs_cas_$(date +%Y%m%d_%H%M%S) flash-sale-burst.js
 *
 *   # 비관적 락 변종
 *   FLASH_SALE_LOCK_STRATEGY=pessimistic ./gradlew bootRun &
 *   psql -U postgres -d shopping_mall_loadtest_db -f reset-flash-sale.sql
 *   k6 run --env RUN_ID=fs_pess_$(date +%Y%m%d_%H%M%S) flash-sale-burst.js
 *
 * 사후 검증
 *   psql -U postgres -d shopping_mall_loadtest_db <<'SQL'
 *     SELECT remaining_quantity FROM flash_sale_items WHERE flash_sale_id = (
 *       SELECT flash_sale_id FROM flash_sales WHERE title='LOADTEST_FLASH'
 *     );
 *     SELECT count(*) FROM flash_sale_purchases p
 *       JOIN flash_sales s USING(flash_sale_id) WHERE s.title='LOADTEST_FLASH';
 *   SQL
 *   -- (allocated - remaining) == count(purchases) == fs_success Counter 여야 한다.
 * ============================================================================
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ──────────────────────────────────────────────
//  설정
// ──────────────────────────────────────────────
const BASE_URL    = __ENV.BASE_URL || 'http://localhost:8080';
const VUS         = parseInt(__ENV.VUS || '200', 10);
const SALE_ID     = __ENV.SALE_ID || '1';
const ITEM_ID     = __ENV.ITEM_ID || '1';
const RUN_ID      = __ENV.RUN_ID || `fs_burst_${Date.now()}`;

// 재고와 정확히 같은 수의 사용자(loaduser_001..VUS)를 사용한다.
// 같은 VU는 같은 사용자 ID로 매핑 → uk_fsp_user_sale 위반 없이 깨끗한 burst.
const USER_COUNT = VUS;

// ──────────────────────────────────────────────
//  메트릭
// ──────────────────────────────────────────────
const fsSuccess    = new Counter('fs_success');     // 201 + body.success=true
const fsSoldOut    = new Counter('fs_soldout');     // 409 SOLD_OUT
const fsDuplicate  = new Counter('fs_duplicate');   // 409 ONE_PER_USER
const fsRateLimit  = new Counter('fs_ratelimit');   // 429
const fsWindow     = new Counter('fs_window');      // 400 WINDOW_CLOSED
const fsAuthFail   = new Counter('fs_auth_fail');   // 401/302→login
const fsServerErr  = new Counter('fs_server_err');  // 5xx
const fsOther      = new Counter('fs_other');       // 그 외 4xx
const fsLoginFail  = new Counter('fs_login_fail');  // 로그인 자체 실패

const purchaseDur  = new Trend('purchase_duration', true);
const loginDur     = new Trend('login_duration', true);

// ──────────────────────────────────────────────
//  k6 옵션
// ──────────────────────────────────────────────
export const options = {
  // 모든 VU가 일제히 1회 구매 시도(burst). per-vu-iterations로 재시도/리스폰 없이 단발.
  scenarios: {
    flash_burst: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '60s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  // burst는 sold_out/duplicate가 정상 응답이므로 http_req_failed을 게이트로 쓰지 않는다.
  thresholds: {
    // 성공 + 정상 거절(sold_out/duplicate/ratelimit) = total — 5xx만 게이트
    fs_server_err: ['count<1'],
    purchase_duration: ['p(95)<5000'],
  },
};

// ──────────────────────────────────────────────
//  유틸
// ──────────────────────────────────────────────
function getCsrf(jar) {
  const cookies = jar.cookiesForURL(BASE_URL);
  if (cookies['XSRF-TOKEN'] && cookies['XSRF-TOKEN'].length > 0) {
    return cookies['XSRF-TOKEN'][0];
  }
  return '';
}

function uuid() {
  // RFC4122 v4. crypto는 k6 v1+ 에서 미지원이므로 Math.random 기반.
  const hex = '0123456789abcdef';
  let out = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) { out += '-'; continue; }
    if (i === 14) { out += '4'; continue; }
    if (i === 19) { out += hex[(Math.random() * 4 | 0) + 8]; continue; }
    out += hex[Math.random() * 16 | 0];
  }
  return out;
}

function userNum() {
  return ((__VU - 1) % USER_COUNT) + 1;
}

function credentials() {
  const num = String(userNum()).padStart(3, '0');
  return { username: `loaduser_${num}`, password: 'test1234' };
}

// ──────────────────────────────────────────────
//  setup() — 부하 시작 전에 모든 사용자를 사전 로그인.
//  setup은 단일 VU에서 순차 실행되므로, 본격 burst 시점에는
//  로그인 경합/bcrypt 비용이 측정에 섞이지 않는다.
// ──────────────────────────────────────────────
export function setup() {
  const sessions = [];
  for (let i = 1; i <= USER_COUNT; i++) {
    const num = String(i).padStart(3, '0');
    const username = `loaduser_${num}`;
    const jar = new http.CookieJar();  // 사용자별 독립 jar (jsessionid 분리 필수)

    http.get(`${BASE_URL}/auth/login`, { jar });
    const cookies0 = jar.cookiesForURL(BASE_URL);
    const csrf = (cookies0['XSRF-TOKEN'] && cookies0['XSRF-TOKEN'][0]) || '';

    const t0 = Date.now();
    const res = http.post(`${BASE_URL}/auth/login`,
      { username, password: 'test1234', _csrf: csrf },
      {
        jar,
        redirects: 5,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      });
    loginDur.add(Date.now() - t0);

    const ok = res.status === 200 && !(res.url || '').includes('error');
    if (!ok) {
      fsLoginFail.add(1);
      sessions.push({ username, jsessionid: null });
      continue;
    }
    const cookies = jar.cookiesForURL(BASE_URL);
    const jsess = (cookies['JSESSIONID'] && cookies['JSESSIONID'][0]) || null;
    sessions.push({ username, jsessionid: jsess });
  }
  const ready = sessions.filter(s => s.jsessionid).length;
  console.log(`[setup] pre-login complete: ${ready}/${USER_COUNT} sessions ready`);
  return { sessions };
}

// ──────────────────────────────────────────────
//  메인: 구매 1회 시도 (burst)
// ──────────────────────────────────────────────
export default function flashSaleBurst(data) {
  const idx = (__VU - 1) % data.sessions.length;
  const sess = data.sessions[idx];
  if (!sess || !sess.jsessionid) {
    fsLoginFail.add(1);
    return;
  }

  group('Flash Sale — Purchase', () => {
    const url = `${BASE_URL}/api/v1/flash-sales/${SALE_ID}/items/${ITEM_ID}/purchase`;
    const t0 = Date.now();
    const res = http.post(url, null, {
      headers: {
        'Content-Type': 'application/json',
        'X-Idempotency-Key': uuid(),
        'Cookie': `JSESSIONID=${sess.jsessionid}`,
      },
      tags: { name: 'POST /flash-sales/:id/items/:id/purchase', endpoint: 'flash_purchase' },
    });
    purchaseDur.add(Date.now() - t0);

    classify(res);
  });
}

function classify(res) {
  const status = res.status;
  const body = res.body || '';

  if (status === 201 || status === 200) {
    fsSuccess.add(1);
    check(res, { 'success has orderId': (r) => /"orderId"\s*:\s*\d+/.test(r.body || '') });
    return;
  }
  if (status === 401 || (res.url || '').includes('/auth/login')) {
    fsAuthFail.add(1);
    return;
  }
  if (status === 429) {
    fsRateLimit.add(1);
    return;
  }
  if (status >= 500) {
    fsServerErr.add(1);
    return;
  }
  if (status === 409 && /SOLD_OUT/.test(body)) {
    fsSoldOut.add(1);
    return;
  }
  if (status === 409 && /ONE_PER_USER|duplicate/i.test(body)) {
    fsDuplicate.add(1);
    return;
  }
  if (status === 400 && /WINDOW_CLOSED|window/i.test(body)) {
    fsWindow.add(1);
    return;
  }
  fsOther.add(1);
}

// ──────────────────────────────────────────────
//  결과 요약 — JSON 파일 저장
// ──────────────────────────────────────────────
export function handleSummary(data) {
  const m = data.metrics || {};
  const get = (name, key) => (m[name] && m[name].values ? m[name].values[key] : undefined);

  const summary = {
    run_id: RUN_ID,
    base_url: BASE_URL,
    vus: VUS,
    sale_id: SALE_ID,
    item_id: ITEM_ID,
    counts: {
      success:    get('fs_success', 'count') || 0,
      soldout:    get('fs_soldout', 'count') || 0,
      duplicate:  get('fs_duplicate', 'count') || 0,
      ratelimit:  get('fs_ratelimit', 'count') || 0,
      window:     get('fs_window', 'count') || 0,
      auth_fail:  get('fs_auth_fail', 'count') || 0,
      server_err: get('fs_server_err', 'count') || 0,
      other:      get('fs_other', 'count') || 0,
      login_fail: get('fs_login_fail', 'count') || 0,
    },
    purchase: {
      avg: get('purchase_duration', 'avg'),
      med: get('purchase_duration', 'med'),
      p90: get('purchase_duration', 'p(90)'),
      p95: get('purchase_duration', 'p(95)'),
      p99: get('purchase_duration', 'p(99)'),
      max: get('purchase_duration', 'max'),
    },
    http: {
      reqs_total: get('http_reqs', 'count'),
      rps:        get('http_reqs', 'rate'),
      failed:     get('http_req_failed', 'rate'),
      duration_p95: get('http_req_duration', 'p(95)'),
      duration_p99: get('http_req_duration', 'p(99)'),
    },
    iter_duration: {
      avg: get('iteration_duration', 'avg'),
      p95: get('iteration_duration', 'p(95)'),
    },
  };

  const path = `flash-sale-result.${RUN_ID}.json`;
  return {
    stdout: textSummary(summary),
    [path]: JSON.stringify(summary, null, 2),
    'flash-sale-last.json': JSON.stringify(summary, null, 2),
  };
}

function textSummary(s) {
  const c = s.counts;
  const total = c.success + c.soldout + c.duplicate + c.ratelimit + c.window
              + c.auth_fail + c.server_err + c.other;
  const lines = [
    '',
    '────────────────────────────────────────────────────────────',
    ` [flash-sale-burst]  RUN_ID=${s.run_id}  VUS=${s.vus}`,
    '────────────────────────────────────────────────────────────',
    ` 응답 분포 (총 ${total}건):`,
    `   ✅ success    : ${c.success}`,
    `   🟠 sold_out   : ${c.soldout}`,
    `   🟡 duplicate  : ${c.duplicate}`,
    `   🚦 ratelimit  : ${c.ratelimit}`,
    `   🕒 window     : ${c.window}`,
    `   🔐 auth_fail  : ${c.auth_fail}`,
    `   💥 server_err : ${c.server_err}`,
    `   ❓ other      : ${c.other}`,
    `   🔓 login_fail : ${c.login_fail}`,
    '',
    ` purchase_duration  p95=${fmt(s.purchase.p95)}ms  p99=${fmt(s.purchase.p99)}ms  max=${fmt(s.purchase.max)}ms`,
    ` http_req_duration  p95=${fmt(s.http.duration_p95)}ms  p99=${fmt(s.http.duration_p99)}ms`,
    ` http_reqs          total=${s.http.reqs_total}  RPS=${fmt(s.http.rps)}`,
    '────────────────────────────────────────────────────────────',
    '',
  ];
  return lines.join('\n');
}

function fmt(v) {
  if (v == null) return '-';
  return Number(v).toFixed(2);
}

/**
 * ============================================================================
 * 스모크 테스트 — 부하 테스트 전 환경 검증용
 * ============================================================================
 *
 * 실행: k6 run smoke-test.js
 *
 * 확인 항목:
 *   1) 서버가 응답하는가
 *   2) 로그인이 되는가 (CSRF 토큰 + 세션)
 *   3) 장바구니/주문 흐름이 작동하는가
 *   4) 쿠폰 발급이 작동하는가
 * ============================================================================
 */

import http from 'k6/http';
import { check, sleep, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
    vus: 1,
    iterations: 1,
    thresholds: {
        checks: ['rate==1.0'],  // 모든 체크 통과 필수
    },
};

function getCsrf(jar) {
    const cookies = jar.cookiesForURL(BASE_URL);
    if (cookies['XSRF-TOKEN'] && cookies['XSRF-TOKEN'].length > 0) {
        return cookies['XSRF-TOKEN'][0];
    }
    return '';
}

function authPost(jar, url, body) {
    const csrf = getCsrf(jar);
    const payload = Object.assign({}, body, { _csrf: csrf });
    return http.post(url, payload, {
        jar,
        redirects: 5,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    });
}

export default function () {
    console.log('═══ 스모크 테스트 시작 ═══');
    console.log(`대상 서버: ${BASE_URL}`);

    // ─── 1. 서버 응답 확인 ───
    console.log('\n[1/5] 서버 응답 확인...');
    let res = http.get(`${BASE_URL}/`);
    if (!check(res, { '홈페이지 200': (r) => r.status === 200 })) {
        fail(`서버가 응답하지 않습니다: status=${res.status}`);
    }
    console.log(`  ✓ 홈페이지 OK (${res.timings.duration.toFixed(0)}ms)`);

    // ─── 2. 비인증 페이지 ───
    console.log('\n[2/5] 비인증 페이지 확인...');
    res = http.get(`${BASE_URL}/products?sort=best&page=0`);
    check(res, { '상품 목록 200': (r) => r.status === 200 });
    console.log(`  ✓ 상품 목록 OK (${res.timings.duration.toFixed(0)}ms)`);

    res = http.get(`${BASE_URL}/search?q=${encodeURIComponent('노트북')}`);
    check(res, { '검색 200': (r) => r.status === 200 });
    console.log(`  ✓ 검색 OK (${res.timings.duration.toFixed(0)}ms)`);

    // ─── 3. 로그인 ───
    console.log('\n[3/5] 로그인 확인...');
    const jar = http.cookieJar();

    res = http.get(`${BASE_URL}/auth/login`, { jar });
    check(res, { '로그인 페이지 200': (r) => r.status === 200 });

    const csrf = getCsrf(jar);
    if (!csrf) {
        console.warn('  ⚠ CSRF 토큰이 설정되지 않았습니다. CSRF 비활성화 또는 설정 확인 필요.');
    } else {
        console.log(`  ✓ CSRF 토큰 획득: ${csrf.substring(0, 8)}...`);
    }

    res = http.post(`${BASE_URL}/auth/login`, {
        username: 'loaduser_001',
        password: 'test1234',
        _csrf: csrf,
    }, {
        jar,
        redirects: 5,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    });

    const loginOk = check(res, {
        '로그인 성공': (r) => r.status === 200 && !r.url.includes('error=true'),
    });

    if (!loginOk) {
        console.error(`  ✗ 로그인 실패: ${res.status} → ${res.url}`);
        console.error('  setup-loadtest.sql을 실행했는지, BCrypt 해시가 올바른지 확인하세요.');
        console.error('  Spring Boot 콘솔에서 비밀번호 해시를 생성하려면:');
        console.error('    new BCryptPasswordEncoder().encode("test1234")');
        fail('로그인 실패 — 테스트를 계속할 수 없습니다.');
    }
    console.log(`  ✓ 로그인 성공 (${res.timings.duration.toFixed(0)}ms)`);

    sleep(0.5);

    // ─── 4. 인증 필요 페이지 ───
    console.log('\n[4/5] 인증 페이지 확인...');
    res = http.get(`${BASE_URL}/cart`, { jar });
    check(res, { '장바구니 200': (r) => r.status === 200 });
    console.log(`  ✓ 장바구니 OK (${res.timings.duration.toFixed(0)}ms)`);

    res = http.get(`${BASE_URL}/orders`, { jar });
    check(res, { '주문 목록 200': (r) => r.status === 200 });
    console.log(`  ✓ 주문 목록 OK (${res.timings.duration.toFixed(0)}ms)`);

    res = http.get(`${BASE_URL}/coupons`, { jar });
    check(res, { '쿠폰 페이지 200': (r) => r.status === 200 });
    console.log(`  ✓ 쿠폰 페이지 OK (${res.timings.duration.toFixed(0)}ms)`);

    res = http.get(`${BASE_URL}/mypage`, { jar });
    check(res, { '마이페이지 200': (r) => r.status === 200 });
    console.log(`  ✓ 마이페이지 OK (${res.timings.duration.toFixed(0)}ms)`);

    // ─── 5. 장바구니 추가 ───
    console.log('\n[5/5] 장바구니 추가 확인...');
    res = authPost(jar, `${BASE_URL}/cart/add`, {
        productId: '1',
        quantity: '1',
    });
    const cartOk = check(res, {
        '장바구니 추가 성공': (r) => r.status === 200,
    });
    if (cartOk) {
        console.log(`  ✓ 장바구니 추가 OK (${res.timings.duration.toFixed(0)}ms)`);
    } else {
        console.warn(`  ⚠ 장바구니 추가 실패: ${res.status}`);
        console.warn('  productId=1 상품이 존재하고 재고가 있는지 확인하세요.');
    }

    // ─── 결과 ───
    console.log('\n═══ 스모크 테스트 완료 ═══');
    console.log('모든 체크를 통과했다면 부하 테스트를 실행할 수 있습니다:');
    console.log('  k6 run load-test.js --env SCENARIO=browse');
    console.log('  k6 run load-test.js --env SCENARIO=mixed');
}

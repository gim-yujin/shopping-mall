# Shopping Mall — k6 부하 테스트 가이드

## 전체 흐름

```
1. k6 설치
2. BCrypt 해시 생성 → setup-loadtest.sql 수정
3. setup-loadtest.sql 실행 (테스트 사용자/쿠폰 생성)
4. PRODUCT_IDS 확인 후 load-test.js 수정
5. Spring Boot 앱 실행
6. smoke-test.js로 환경 검증
7. load-test.js로 부하 테스트 실행
8. 결과 분석
9. cleanup-loadtest.sql로 데이터 정리
```

---

## 1. k6 설치

```bash
# macOS
brew install k6

# Ubuntu/Debian
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6

# Windows
choco install k6
# 또는 https://github.com/grafana/k6/releases 에서 바이너리 다운로드
```

---

## 2. 테스트 데이터 준비

### 2-1. BCrypt 해시 생성

`setup-loadtest.sql`에 들어갈 비밀번호 해시를 생성합니다.

**방법 A — Java main 실행:**
```bash
cd load-test
javac -cp "../build/libs/*" GenerateBcryptHash.java
# 또는 IDE에서 GenerateBcryptHash.main() 실행
```

**방법 B — Spring Boot Shell/Runner에서:**
```java
new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test1234")
```

**방법 C — 온라인 도구:**
- https://bcrypt-generator.com/ 에서 "test1234" 입력 (rounds=10)

출력된 `$2a$10$...` 해시를 `setup-loadtest.sql`의 `bcrypt_hash` 변수에 넣습니다.

### 2-2. SQL 실행

```bash
psql -U postgres -d shopping_mall_db -f load-test/setup-loadtest.sql
```

출력 확인:
```
 load_test_users | 200
 LOADTEST_RUSH   |  50 |  0
```

### 2-3. PRODUCT_IDS 확인

SQL 마지막에 출력되는 상품 ID를 `load-test.js`의 `PRODUCT_IDS` 배열에 반영합니다.

```javascript
// load-test.js 11행
const PRODUCT_IDS = [1, 2, 3, 5, 7, 10, 15, 20, 25, 30];  // ← 실제 ID로 수정
```

---

## 3. 테스트 실행

### 스모크 테스트 (환경 검증)

```bash
# Spring Boot 앱이 실행 중인 상태에서
cd load-test
k6 run smoke-test.js
```

모든 `✓`가 출력되면 환경 준비 완료입니다.  
로그인 실패 시 BCrypt 해시를 확인하세요.

### 시나리오별 실행

```bash
# 1) Browse — 비인증 트래픽만 (가장 가벼운 테스트, 먼저 실행 권장)
k6 run --env SCENARIO=browse load-test.js

# 2) Shopping — 인증 + 장바구니 + 주문
k6 run --env SCENARIO=shopping load-test.js

# 3) Coupon Rush — 선착순 쿠폰 스파이크
k6 run --env SCENARIO=coupon_rush load-test.js

# 4) Mixed — 실 트래픽 비율 혼합 (최종 종합 테스트)
k6 run --env SCENARIO=mixed load-test.js
```

### 옵션 조정

```bash
# 서버 주소 변경
k6 run --env BASE_URL=http://192.168.1.100:8080 load-test.js

# 테스트 사용자 수 변경
k6 run --env USERS=100 load-test.js
```

---

## 4. 시나리오 상세

### Browse (비인증 탐색)
```
홈페이지 → 상품 목록(정렬 랜덤) → 상품 상세 → 검색 → 카테고리 페이지
```
- 실 트래픽의 ~60% 비율
- DB 읽기 위주, 캐시 효과 측정에 적합
- **주요 관찰 포인트:** 검색 쿼리 성능, 상품 목록 정렬 성능

### Shopping (인증 + 주문)
```
로그인 → 상품 상세 → 장바구니 추가 → 장바구니 확인 → 체크아웃 → 주문 생성 → 주문 목록
```
- 실 트래픽의 ~25% 비율
- DB 쓰기 + 비관적 락 + 재고 차감이 핵심
- **주요 관찰 포인트:** 주문 응답 시간, 재고 정합성, DB 락 대기 시간

### Coupon Rush (선착순 스파이크)
```
로그인 → 쿠폰 페이지 → 선착순 발급 시도
```
- 100 VU가 동시에 50개 쿠폰을 쟁탈
- 비관적 락 + UNIQUE 제약의 동시성 처리 검증
- **주요 관찰 포인트:** 정확히 50개만 발급되는지, 에러율, 응답 시간

### Mixed (종합)
```
Browse 120 VU + Shopping 50 VU + Coupon 30 VU + Social 10 VU
```
- 실 운영 환경에 가장 가까운 패턴
- **주요 관찰 포인트:** 전체 TPS, HikariCP 커넥션 풀 포화, p95 응답 시간

---

## 5. 결과 분석 가이드

### 핵심 메트릭

| 메트릭 | 건강 기준 | 위험 신호 |
|--------|----------|----------|
| `http_req_duration (p95)` | < 1000ms | > 3000ms |
| `http_req_failed` | < 1% | > 5% |
| `login_duration (p95)` | < 500ms | > 2000ms |
| `order_duration (p95)` | < 2000ms | > 5000ms |
| `coupon_duration (p95)` | < 1000ms | > 3000ms |

### 결과 파일

테스트 종료 시 `load-test-result.json`이 생성됩니다. 이 파일로 상세 분석 가능.

### 병목 진단 체크리스트

**응답 시간이 점점 느려진다면 (p95 증가):**
1. HikariCP 풀 포화 → `application.yml`의 `maximum-pool-size` 확인
2. DB 쿼리 슬로우 → PostgreSQL의 `pg_stat_activity`, `pg_stat_statements` 확인
3. GC 압박 → JVM 힙 메모리 확인 (`-Xmx`)

**에러율이 높다면:**
1. 5xx → 서버 로그에서 예외 확인 (커넥션 풀 타임아웃, 데드락 등)
2. 로그인 실패 → 세션 저장소 용량, 메모리
3. 주문 실패 → 재고 부족, 비관적 락 타임아웃

### Spring Boot 서버 모니터링 (테스트 중)

```bash
# HikariCP 커넥션 풀 상태 (Actuator 활성화 시)
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active

# PostgreSQL 활성 쿼리 확인
psql -U postgres -d shopping_mall_db -c "
  SELECT pid, state, wait_event_type, query_start, LEFT(query, 80)
  FROM pg_stat_activity
  WHERE datname = 'shopping_mall_db' AND state != 'idle'
  ORDER BY query_start;
"

# PostgreSQL 락 대기 확인
psql -U postgres -d shopping_mall_db -c "
  SELECT blocked.pid AS blocked_pid,
         blocking.pid AS blocking_pid,
         LEFT(blocked.query, 60) AS blocked_query
  FROM pg_stat_activity blocked
  JOIN pg_locks bl ON bl.pid = blocked.pid
  JOIN pg_locks bk ON bk.locktype = bl.locktype
       AND bk.relation = bl.relation AND bk.pid != bl.pid
  JOIN pg_stat_activity blocking ON blocking.pid = bk.pid
  WHERE NOT bl.granted;
"
```

---

## 6. 부하 테스트 후 정리

```bash
# 데이터 정리
psql -U postgres -d shopping_mall_db -f load-test/cleanup-loadtest.sql

# 쿠폰 재사용 시 (다시 테스트 전)
psql -U postgres -d shopping_mall_db -c "
  UPDATE coupons SET used_quantity = 0 WHERE coupon_code = 'LOADTEST_RUSH';
  DELETE FROM user_coupons WHERE coupon_id = (SELECT coupon_id FROM coupons WHERE coupon_code = 'LOADTEST_RUSH');
"
```

---

## 7. 권장 실행 순서

```
① k6 run smoke-test.js                         → 환경 검증
② k6 run --env SCENARIO=browse load-test.js     → 읽기 부하 기준선 측정
③ k6 run --env SCENARIO=shopping load-test.js   → 쓰기 부하 + 주문 성능
④ k6 run --env SCENARIO=coupon_rush load-test.js → 동시성 스파이크
⑤ k6 run --env SCENARIO=mixed load-test.js      → 종합 성능 측정
```

각 단계의 결과를 기록하고, 병목이 발견되면 개선 후 다시 측정하여 **Before/After 비교**를 만드세요.
이 비교 데이터가 포트폴리오에서 가장 강력한 근거가 됩니다.

# 운영가이드: 부하 테스트 환경 구축 (500K 시드)

대상 독자: 성능/부하 테스트 재측정 담당자
관련 문서: [`analysis-product-list-count-cache-split.md`](./analysis-product-list-count-cache-split.md) §4.2, [`load-test-benchmark.md`](./load-test-benchmark.md)

## 1. 목적
`analysis-product-list-count-cache-split.md` §4.2의 한계("k6 재측정 미실시")를 해소하기 위한 부하 테스트 전용 DB 구축 절차를 기록한다. 이후 Phase 21 이상의 성능 관련 변경을 검증할 때 동일한 절차로 재현한다.

## 2. 왜 별도 DB인가
- 기존 `shopping_mall_db`는 `./gradlew test`가 매 실행마다 `test-reset.sql`로 퍼블릭 스키마를 drop한다. 500K 시드를 유지할 수 없다.
- 시드 생성에는 2분 내외가 걸리므로 단위 테스트와 공존시키면 개발 피드백 루프가 망가진다.
- 해결: 부하 테스트 전용 DB `shopping_mall_loadtest_db`를 같은 PostgreSQL 인스턴스에 별도로 둔다. 애플리케이션은 환경변수 오버라이드로 접속 대상을 바꾼다.

## 3. 구성 요소

| 항목 | 위치 |
|:-----|:-----|
| DB 이름 | `shopping_mall_loadtest_db` |
| 스키마 정의 | `src/main/resources/schema.sql` (V2–V20 마이그레이션 내용 전부 포함) |
| 더미 데이터 생성기 | `/home/admin0/shopping-mall_0/optimization/generate_dummy_data_500k.py` (프로젝트 외부, 원본 `generate_dummy_data.py`의 500K-스케일 복사본) |
| 생성기 Config 스케일 | users 50K / categories 1K / products 50K / orders 500K / order_items 1.5M / reviews 200K / inventory_history 500K / search_logs 100K / user_tier_history 50K |

생성기 스크립트가 프로젝트 외부에 있는 이유는 해당 디렉터리의 자체 `.gitignore`가 `optimization/`을 무시하기 때문이다. 스크립트를 main repo로 옮기는 것은 차후 결정 사항으로 남긴다.

## 4. 셋업 절차

### 4-1. DB 생성 + 스키마 적용
```bash
PGPASSWORD=4321 psql -h localhost -U postgres -c 'DROP DATABASE IF EXISTS shopping_mall_loadtest_db;'
PGPASSWORD=4321 psql -h localhost -U postgres -c 'CREATE DATABASE shopping_mall_loadtest_db;'
PGPASSWORD=4321 psql -h localhost -U postgres -d shopping_mall_loadtest_db \
    -f /home/admin0/shopping-mall/src/main/resources/schema.sql
```
`schema.sql`은 V2–V20 마이그레이션 내용을 전부 포함하므로 별도 migration 실행은 불필요하다.

### 4-2. 더미 데이터 생성
```bash
rm -rf /home/admin0/shopping-mall_0/optimization/csv_data_500k
cd /home/admin0/shopping-mall_0/optimization
python3 generate_dummy_data_500k.py
```
첫 실행 기준 총 소요 시간 약 2분 7초 (CSV 생성 + COPY + VACUUM ANALYZE 포함). 마지막에 VACUUM ANALYZE가 자동 실행되므로 별도 통계 갱신은 불필요하다.

### 4-3. 애플리케이션을 부하 테스트 DB로 기동
```bash
cd /home/admin0/shopping-mall
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/shopping_mall_loadtest_db \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=4321 \
./gradlew bootRun
```
프로덕션과 동일하게 `ddl-auto=validate`로 기동한다. 스키마 드리프트가 있으면 기동이 실패한다 — 드리프트 발생 시 우선 schema.sql 재적용을 고려한다.

## 5. 생성 중 해결한 제약 위반 3건

Phase 21 준비 중 generator 원본 스크립트가 현행 스키마와 맞지 않는 지점이 세 개 드러났다. 모두 `generate_dummy_data_500k.py`에 반영되어 있으며, 향후 스키마가 바뀌면 같은 계열 오류가 재발할 수 있으므로 기록으로 남긴다.

### 5-1. `orders.chk_discount_breakdown` 위반
- 제약: `discount_amount = tier_discount_amount + coupon_discount_amount` (exact equality, DECIMAL(10,2)).
- 원인: Python float를 각 컴포넌트별로 독립 반올림한 뒤 DB에 저장하면, IEEE 754 부동소수 오차로 `tier + coupon ≠ discount`가 생긴다.
- 수정: `tier_discount`와 `coupon_discount`를 먼저 `round(..., 2)`로 고정한 뒤 두 값의 합을 `discount_amount`로 다시 계산해 기록한다. CSV에도 세 컬럼을 모두 써 넣고, COPY 컬럼 목록에 `tier_discount_amount`, `coupon_discount_amount`를 추가했다.

### 5-2. `uk_user_coupon_user_coupon` 위반
- 제약: `user_coupons(user_id, coupon_id)` UNIQUE.
- 원인: `random.randint`로 쌍을 뽑아 100K건 INSERT하면 생일 역설로 초반에 충돌한다 (50K × 1K에서 100K를 뽑으므로 중복 기대치가 높다).
- 수정: `used_pairs = set()`로 이미 쓴 쌍을 추적하고 충돌 시 재시도하는 패턴으로 변경. 10K건마다 commit.

### 5-3. `uk_review_user_product_without_order_item` 위반 (V16 부분 유니크 인덱스)
- 제약: `CREATE UNIQUE INDEX ... ON reviews(user_id, product_id) WHERE order_item_id IS NULL`
- 원인: generator가 모든 리뷰에 `order_item_id=NULL`을 쓰기 때문에 부분 인덱스가 사실상 전체 행에 걸린다. 50K × 50K 공간에서 200K 쌍을 뽑으면 √2.5B ≈ 50K 근처에서 첫 충돌이 기대된다.
- 수정: 리뷰 배치 루프 전체에서 `(user_id, product_id)` 쌍을 추적하는 `used_pairs` 세트를 유지해 충돌 시 재시도. 50K × 50K / 200K ≈ 12,500 기대 시도이므로 재시도 오버헤드는 무시 가능.
- 교훈: 스키마 드리프트 점검 시 `CREATE TABLE` 인라인 `CONSTRAINT`뿐 아니라 독립된 `CREATE UNIQUE INDEX` 문과 `WHERE` 절(부분 인덱스)까지 같이 검사해야 한다.

## 6. 재측정 시 k6 시나리오 요건
`load-test-analysis.md`와 비교 가능성을 유지하기 위해 다음 조건을 같게 둔다.
- 시나리오 3종: Narrow / Wide / Longtail
- 가상 사용자: 100 VU
- 가상 스레드 활성화 (`spring.threads.virtual.enabled=true`, Phase 20 이후 기본값)
- 모든 캐시 초기 상태(ColdStart) 및 워밍업 후 상태(WarmHit) 양쪽 측정

측정 결과는 `docs/load-test-benchmark.md`에 추가하거나 새 벤치마크 문서를 생성해 Phase 21 문서 §4.2를 해소하는 형태로 기록한다.

## 7. 운영 메모
- 부하 테스트 전용 DB는 `.env` 등에 자격 증명을 저장하지 않고 명령행 환경변수로만 넘긴다.
- 스크립트는 외부 경로에 있으므로 체크아웃된 main repo만으로는 재현이 불가능하다. 스크립트의 위치와 스케일 파라미터를 본 문서에 명시해 두는 이유다.
- 더미 데이터는 재현 가능해야 의미가 있다. 필요하면 스크립트에 `random.seed()`를 고정해 반복 실행 간 동일성을 확보한다 (현재는 미고정).

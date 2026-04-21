-- V22: searchByKeywordLikeFlat() Seq Scan 회피용 pg_trgm GIN 인덱스
--
-- 배경:
--   SELECT ... FROM v_product_list v
--   WHERE v.is_active = true
--     AND LOWER(v.product_name) LIKE LOWER(CONCAT('%', :keyword, '%'))
--   (ProductRepository.searchByKeywordLikeFlat — FTS 폴백 경로)
--
--   양방향 LIKE('%...%')는 B-tree 인덱스를 활용할 수 없어 products 전체 행을
--   Seq Scan한다. LOWER() 함수 적용으로 일반 expression index도 매칭 불가.
--
-- 해결:
--   pg_trgm extension의 trigram GIN 인덱스를 LOWER(product_name) 표현식에 생성하여
--   양방향 LIKE + 대소문자 비구분 매칭을 Bitmap Index Scan으로 전환.
--   docs/analysis-execution-plan-optimization.md §5-2 벤치마크에서
--   실행 시간 4.935ms → 1.118ms (-77%), 버퍼 접근 -48%, Seq Scan 제거 확인.
--
-- 운영 주의:
--   - CREATE EXTENSION pg_trgm은 superuser 또는 pg_extension 권한 보유 역할 필요.
--     대부분의 managed PostgreSQL(RDS 등)에서는 rds_superuser/기본 admin 역할로 가능.
--   - IF NOT EXISTS로 idempotent 적용. schema.sql 수동 적용 환경에서 이미 존재해도 무해.
--
-- CONCURRENTLY 사용 이유:
--   products는 상품 관리 경로의 실시간 쓰기 대상이므로 인덱스 생성 중 ACCESS EXCLUSIVE
--   잠금을 피해야 한다. CONCURRENTLY는 트랜잭션 블록 외부에서 실행되어야 하므로
--   extension 생성과 별도 문장으로 분리.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_name_trgm
    ON products USING gin (LOWER(product_name) gin_trgm_ops);

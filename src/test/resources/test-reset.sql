-- 테스트 실행 전 항상 깨끗한 스키마에서 시작해 로컬/CI 결과를 일치시킨다.
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;

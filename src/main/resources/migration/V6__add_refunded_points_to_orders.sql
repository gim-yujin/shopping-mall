-- V6: Add refunded_points column to orders table
--
-- [P0-2] 부분취소/반품 시 포인트 비례 환불 추적.
-- 기존에는 부분 취소 시 포인트 환불이 전혀 없었다.
-- 이 컬럼은 비례 환불된 포인트 누계를 추적하여 초과 환불을 방지한다.

ALTER TABLE orders ADD COLUMN refunded_points INT DEFAULT 0 NOT NULL;

COMMENT ON COLUMN orders.refunded_points IS '부분취소/반품 누적 환불 포인트 (비례 배분, 초과 환불 방지용)';

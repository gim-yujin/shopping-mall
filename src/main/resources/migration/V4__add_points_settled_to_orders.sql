-- Migration: Add points_settled column to orders table
-- Purpose: Align production schema with application schema.sql

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS points_settled BOOLEAN DEFAULT FALSE;

UPDATE orders
SET points_settled = FALSE
WHERE points_settled IS NULL;

ALTER TABLE orders
    ALTER COLUMN points_settled SET DEFAULT FALSE,
    ALTER COLUMN points_settled SET NOT NULL;

COMMENT ON COLUMN orders.points_settled IS '포인트 정산 완료 여부 (배송 완료 시 TRUE로 전환, 중복 정산 방지)';

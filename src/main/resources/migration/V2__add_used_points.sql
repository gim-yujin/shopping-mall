-- Migration: Add used_points column to orders table
-- Run this on existing databases before deploying the points-usage feature.

ALTER TABLE orders ADD COLUMN IF NOT EXISTS used_points INT DEFAULT 0 NOT NULL;

COMMENT ON COLUMN orders.used_points IS '주문 시 사용한 포인트 (1P = 1원, 취소 시 환불)';

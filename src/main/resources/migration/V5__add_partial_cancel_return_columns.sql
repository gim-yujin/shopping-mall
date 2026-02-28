ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS refunded_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL;

COMMENT ON COLUMN orders.refunded_amount IS '부분취소/반품/전체취소 누적 환불 금액';

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS cancelled_quantity INT DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS returned_quantity INT DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS cancelled_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS returned_amount DECIMAL(15, 2) DEFAULT 0 NOT NULL;

COMMENT ON COLUMN order_items.cancelled_quantity IS '부분 취소된 수량';
COMMENT ON COLUMN order_items.returned_quantity IS '반품된 수량';
COMMENT ON COLUMN order_items.cancelled_amount IS '부분 취소 누적 금액';
COMMENT ON COLUMN order_items.returned_amount IS '반품 누적 금액';

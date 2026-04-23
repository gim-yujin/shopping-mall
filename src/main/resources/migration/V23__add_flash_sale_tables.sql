-- V23: 플래시 세일(타임세일) 기능을 위한 테이블 추가
--
-- 배경:
--   docs/backlog-flash-sale.md 설계의 Phase 23-1.
--   일반 재고(products.stock_quantity)와 격리된 세일 전용 할당량을 관리하여
--   burst 부하가 일반 주문 경로의 재고 row에 경합하지 않도록 한다.
--
-- 테이블:
--   flash_sales            — 세일 이벤트 (일정·상태)
--   flash_sale_items       — 세일 상품 · 할당량 (CAS 대상 remaining_quantity)
--   flash_sale_purchases   — 1인 1구매 감사 로그 (UNIQUE로 중복 구매 차단)
--
-- 운영:
--   DDL 트랜잭션 블록 내 CREATE TABLE은 CONCURRENTLY 불가 — 빈 테이블이므로 불필요.
--   ddl-auto=validate 환경 배포는 본 마이그레이션 수동 적용 필수.

CREATE TABLE flash_sales (
    flash_sale_id   BIGSERIAL    PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    start_time      TIMESTAMP    NOT NULL,
    end_time        TIMESTAMP    NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         INT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_flash_sale_status
        CHECK (status IN ('SCHEDULED','ACTIVE','ENDED','CANCELLED')),
    CONSTRAINT chk_flash_sale_time
        CHECK (end_time > start_time)
);

COMMENT ON TABLE flash_sales IS '플래시 세일(타임세일) 이벤트';
COMMENT ON COLUMN flash_sales.status IS 'SCHEDULED|ACTIVE|ENDED|CANCELLED';

CREATE TABLE flash_sale_items (
    flash_sale_item_id   BIGSERIAL     PRIMARY KEY,
    flash_sale_id        BIGINT        NOT NULL,
    product_id           BIGINT        NOT NULL,
    sale_price           DECIMAL(12,2) NOT NULL,
    allocated_quantity   INT           NOT NULL,
    remaining_quantity   INT           NOT NULL,
    per_user_limit       INT           NOT NULL DEFAULT 1,
    version              INT           NOT NULL DEFAULT 0,

    CONSTRAINT fk_fsi_flash_sale FOREIGN KEY (flash_sale_id)
        REFERENCES flash_sales(flash_sale_id) ON DELETE CASCADE,
    CONSTRAINT fk_fsi_product FOREIGN KEY (product_id)
        REFERENCES products(product_id),
    CONSTRAINT chk_fsi_remaining CHECK (remaining_quantity >= 0),
    CONSTRAINT chk_fsi_allocated CHECK (allocated_quantity > 0),
    CONSTRAINT chk_fsi_price CHECK (sale_price >= 0),
    CONSTRAINT chk_fsi_per_user CHECK (per_user_limit >= 1),
    CONSTRAINT uk_fsi_sale_product UNIQUE (flash_sale_id, product_id)
);

COMMENT ON TABLE flash_sale_items IS '세일 대상 상품 및 할당 재고 (CAS 대상 remaining_quantity)';
COMMENT ON COLUMN flash_sale_items.remaining_quantity IS 'CAS 원자 감분 대상. 0 도달 시 sold_out';

CREATE TABLE flash_sale_purchases (
    flash_sale_purchase_id  BIGSERIAL PRIMARY KEY,
    flash_sale_id           BIGINT    NOT NULL,
    user_id                 BIGINT    NOT NULL,
    order_id                BIGINT    NOT NULL,
    purchased_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fsp_flash_sale FOREIGN KEY (flash_sale_id)
        REFERENCES flash_sales(flash_sale_id),
    CONSTRAINT fk_fsp_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    CONSTRAINT fk_fsp_order FOREIGN KEY (order_id) REFERENCES orders(order_id),
    CONSTRAINT uk_fsp_user_sale UNIQUE (flash_sale_id, user_id)
);

COMMENT ON TABLE flash_sale_purchases IS '1인 1구매 감사 로그. uk_fsp_user_sale가 중복 구매의 최종 방어선';

CREATE INDEX idx_flash_sale_status_start ON flash_sales(status, start_time);
CREATE INDEX idx_fsi_flash_sale          ON flash_sale_items(flash_sale_id);
CREATE INDEX idx_fsp_flash_sale          ON flash_sale_purchases(flash_sale_id, purchased_at DESC);

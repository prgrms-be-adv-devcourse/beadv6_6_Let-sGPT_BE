-- entity_specification.md 결제 도메인 6개 테이블 (wallets, wallet_transactions, wallet_charges,
-- payments, payment_events, refunds). 테이블명 복수형 컨벤션(A11) 적용.

CREATE TABLE payment.wallets (
    id           UUID PRIMARY KEY,
    member_id    UUID NOT NULL UNIQUE,
    balance      BIGINT NOT NULL,
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP
);

CREATE TABLE payment.wallet_transactions (
    id               UUID PRIMARY KEY,
    wallet_id        UUID NOT NULL REFERENCES payment.wallets (id),
    type             VARCHAR(20) NOT NULL,
    amount           BIGINT NOT NULL,
    balance_after    BIGINT NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE payment.wallet_charges (
    id               UUID PRIMARY KEY,
    member_id        UUID NOT NULL,
    amount           BIGINT NOT NULL,
    method           VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    pg_payment_key   VARCHAR(500),
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP
);

CREATE TABLE payment.payments (
    id               UUID PRIMARY KEY,
    order_id         UUID NOT NULL,
    member_id        UUID NOT NULL,
    seller_id        UUID,
    product_id       UUID,
    amount           BIGINT NOT NULL,
    method           VARCHAR(20) NOT NULL,
    pg_provider      VARCHAR(20),
    pg_payment_key   VARCHAR(500),
    pg_tx_id         VARCHAR(100),
    status           VARCHAR(20) NOT NULL,
    refunded_amount  BIGINT NOT NULL DEFAULT 0,
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    approved_at      TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP
);

-- "한 주문 성공 결제 1건" 보장 (research.md §10.5 #14, 멱등키와는 별개 층)
CREATE UNIQUE INDEX uq_payments_order_id_approved
    ON payment.payments (order_id)
    WHERE status = 'APPROVED';

CREATE TABLE payment.payment_events (
    id           UUID PRIMARY KEY,
    payment_id   UUID NOT NULL REFERENCES payment.payments (id),
    type         VARCHAR(20) NOT NULL,
    amount       BIGINT NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE payment.refunds (
    id               UUID PRIMARY KEY,
    payment_id       UUID NOT NULL REFERENCES payment.payments (id),
    amount           BIGINT NOT NULL,
    status           VARCHAR(20) NOT NULL,
    reason           VARCHAR(255),
    pg_refund_key    VARCHAR(500),
    idempotency_key  VARCHAR(100) NOT NULL UNIQUE,
    completed_at     TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

-- Day2: WALLET 결제/MOCK 충전 멱등 바디대조(#7)용 request_hash, outbox 최소 버전.

ALTER TABLE payment.payments ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE payment.wallet_charges ADD COLUMN request_hash VARCHAR(64);

CREATE TABLE payment.outbox_events (
    id             UUID PRIMARY KEY,
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id   UUID NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        TEXT NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    published_at   TIMESTAMP
);

CREATE INDEX idx_outbox_events_status ON payment.outbox_events (status);

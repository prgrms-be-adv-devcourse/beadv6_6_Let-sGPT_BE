-- Day5: 충전 PG confirm 흐름(E1) — payments.pg_payment_key_hash(V3)/pg_tx_id와 동일한 목적으로 wallet_charges에도 추가.

ALTER TABLE payment.wallet_charges ADD COLUMN pg_payment_key_hash VARCHAR(64);
ALTER TABLE payment.wallet_charges ADD COLUMN pg_tx_id VARCHAR(100);

CREATE INDEX idx_wallet_charges_pg_payment_key_hash ON payment.wallet_charges (pg_payment_key_hash);

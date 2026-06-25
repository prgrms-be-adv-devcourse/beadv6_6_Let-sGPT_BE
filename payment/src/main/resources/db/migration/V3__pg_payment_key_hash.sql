-- pg_payment_key는 AES-GCM(비결정적 IV)으로 암호화돼 있어 등호 조회가 불가능 — 웹훅 매칭용으로
-- 평문의 SHA-256 해시(결정적)를 별도 컬럼에 보관해서 그걸로 조회한다.
ALTER TABLE payment.payments ADD COLUMN pg_payment_key_hash VARCHAR(64);
CREATE INDEX idx_payments_pg_payment_key_hash ON payment.payments (pg_payment_key_hash);

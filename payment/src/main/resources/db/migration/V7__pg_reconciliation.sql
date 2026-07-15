-- PG 대사(WS-0) — payments/refunds에 대사 상태 컬럼 추가 + 대사 불일치 기록 테이블.
-- 정산 대사 일별 API(reconciliation.md)는 pg_recon_status='MATCHED' 행만 노출한다.
-- 기존 행(WALLET 결제/환불 등)은 컬럼 추가 시점에 전부 NOT_CHECKED로 시작 — 아래 백필로 WALLET은 즉시 MATCHED 처리.

ALTER TABLE payment.payments
    ADD COLUMN pg_recon_status VARCHAR(20) NOT NULL DEFAULT 'NOT_CHECKED',
    ADD COLUMN pg_reconciled_at TIMESTAMP;

ALTER TABLE payment.refunds
    ADD COLUMN pg_recon_status VARCHAR(20) NOT NULL DEFAULT 'NOT_CHECKED',
    ADD COLUMN pg_reconciled_at TIMESTAMP;

-- 백필 — WALLET 결제/환불은 PG 호출 자체가 없어 대사 대상이 아님, 기존 행도 즉시 MATCHED로 확정.
UPDATE payment.payments SET pg_recon_status = 'MATCHED', pg_reconciled_at = now()
 WHERE method = 'WALLET' AND pg_recon_status = 'NOT_CHECKED';

UPDATE payment.refunds SET pg_recon_status = 'MATCHED', pg_reconciled_at = now()
 WHERE payment_id IN (SELECT id FROM payment.payments WHERE method = 'WALLET')
   AND pg_recon_status = 'NOT_CHECKED';

CREATE INDEX idx_payments_status_approved_recon
    ON payment.payments (status, approved_at, pg_recon_status);

CREATE INDEX idx_refunds_status_completed_recon
    ON payment.refunds (status, completed_at, pg_recon_status);

-- PG 대사 불일치 기록(payment 담당 처리 대상, WS-0.4) — outbox_events와 동일하게 도메인 포트 없이 직접 사용.
CREATE TABLE payment.reconciliation_discrepancies (
    id                UUID PRIMARY KEY,
    business_date     DATE NOT NULL,
    entity_type       VARCHAR(20) NOT NULL,  -- PAYMENT / REFUND
    entity_id         UUID NOT NULL,
    discrepancy_type  VARCHAR(30) NOT NULL,  -- NOT_FOUND_IN_PG / STATUS_MISMATCH / AMOUNT_MISMATCH
    detail            VARCHAR(500),
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_discrepancies_business_date
    ON payment.reconciliation_discrepancies (business_date);

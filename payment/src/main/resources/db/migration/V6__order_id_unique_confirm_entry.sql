-- 7/13 confirm 단일 진입점 — order_id 전체 유니크(get-or-create 예약 키). 기존 부분 인덱스는 부분집합이 되어 제거.
-- dev DB 전제: 같은 order_id 중복 행(과거 FAILED 다수 등)은 최신 1건만 남기고 정리.
-- payment_events/refunds가 payments를 FK 참조하므로, 정리 대상 payments 행을 참조하는 자식 행을 먼저 지운다.
DELETE FROM payment.payment_events
 WHERE payment_id IN (
    SELECT p.id FROM payment.payments p
    JOIN payment.payments newer ON p.order_id = newer.order_id AND p.created_at < newer.created_at
 );

DELETE FROM payment.refunds
 WHERE payment_id IN (
    SELECT p.id FROM payment.payments p
    JOIN payment.payments newer ON p.order_id = newer.order_id AND p.created_at < newer.created_at
 );

DELETE FROM payment.payments p
 USING payment.payments newer
 WHERE p.order_id = newer.order_id AND p.created_at < newer.created_at;

DROP INDEX IF EXISTS payment.uq_payments_order_id_approved;
ALTER TABLE payment.payments ADD CONSTRAINT uq_payments_order_id UNIQUE (order_id);

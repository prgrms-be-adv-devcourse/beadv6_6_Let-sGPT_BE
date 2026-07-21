-- OrderFailCode에 PAYMENT_ALREADY_REFUNDED 추가(TTL 스캐너가 REFUNDED/PARTIALLY_REFUNDED
-- 결제를 발견했을 때 정확한 사유를 남기기 위함). V1(=이 PR 이전 상태)의 CHECK 제약을 갱신한다 —
-- baseline-on-migrate로 V1을 건너뛴 기존 환경도 이 V2는 그대로 적용받는다.

ALTER TABLE orders.orders DROP CONSTRAINT IF EXISTS orders_fail_code_check;

ALTER TABLE orders.orders ADD CONSTRAINT orders_fail_code_check CHECK (fail_code IN (
    'SOLD_OUT', 'DROP_NOT_OPEN', 'DROP_CLOSED', 'LIMIT_EXCEEDED',
    'PAYMENT_FAILED', 'PAYMENT_EXPIRED', 'PAYMENT_ALREADY_REFUNDED', 'PAYMENT_NO_RESPONSE',
    'PG_ERROR', 'PRODUCT_INTEGRATION_FAILED', 'REFUND_REQUEST_FAILED', 'STOCK_ROLLBACK_FAILED'
));

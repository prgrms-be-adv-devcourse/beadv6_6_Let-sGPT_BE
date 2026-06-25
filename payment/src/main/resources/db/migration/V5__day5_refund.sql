-- Day5: 환불(E2) — 멱등 바디대조(#7, A9 범위를 환불까지 확장) 위한 request_hash.
-- pgRefundKeyHash는 불필요 — 환불 PG 웹훅 payload(api_event_specification.md)가 refundId를 직접 포함해서
-- pgPaymentKey/pgPaymentKeyHash 같은 해시 기반 매칭이 필요했던 결제/충전과 달리 findById로 바로 매칭 가능.

ALTER TABLE payment.refunds ADD COLUMN request_hash VARCHAR(64);

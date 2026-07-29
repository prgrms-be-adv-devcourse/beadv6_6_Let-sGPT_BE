-- 분산 트레이스 연결용. 적재 시점의 W3C traceparent(최대 55자)를 저장해 폴링 발행 시 원 요청
-- 트레이스에 producer 스팬을 잇는다. nullable — 트레이스 비활성 경로/기존 행은 종전대로 동작하며,
-- 무중단 배포를 위해 컬럼을 먼저(구버전 코드도 무시) 추가한 뒤 코드를 배포한다.
ALTER TABLE payment.outbox_events ADD COLUMN trace_parent VARCHAR(64);

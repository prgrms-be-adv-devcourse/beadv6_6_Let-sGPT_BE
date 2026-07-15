-- 주문 파이널 PR 1(스키마 + 사가 상태 기록)의 운영 재현용 DDL.
-- order 서비스는 Flyway를 쓰지 않고 ddl-auto: update로 스키마를 관리하므로 이 파일 자체가
-- 스키마의 원천(source of truth)은 아니다 — 실제 컬럼/제약은 엔티티(JPA)가 기준이며,
-- 이 스크립트는 컨테이너 최초 기동(빈 볼륨) 시 테이블을 미리 준비해 운영 환경 재현성을 돕는 용도다.
-- orders.orders에 대한 컬럼 추가는 최초 기동 시점엔 테이블이 없어 no-op이고, 이미 떠 있는 DB에
-- 수동으로 재실행할 때(= create-schemas.sh와 같은 용도)만 실제로 적용된다.

DO $$
BEGIN
    IF to_regclass('orders.orders') IS NOT NULL THEN
        ALTER TABLE orders.orders ADD COLUMN IF NOT EXISTS saga_id VARCHAR(64);
        CREATE INDEX IF NOT EXISTS idx_orders_saga_id ON orders.orders (saga_id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS orders.order_saga_states (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    saga_id VARCHAR(64) NOT NULL,
    current_step VARCHAR(50) NOT NULL,
    compensating_since TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_order_saga_states_order_id UNIQUE (order_id)
);

CREATE TABLE IF NOT EXISTS orders.inbox_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uk_inbox_events_event_id UNIQUE (event_id)
);

CREATE TABLE IF NOT EXISTS orders.outbox_events (
    id UUID PRIMARY KEY,
    topic VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

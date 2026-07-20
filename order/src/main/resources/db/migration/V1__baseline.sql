-- order 서비스 스키마 베이스라인. ddl-auto: update로 관리되던, 이번 PR 직전까지의 스키마를 그대로 고정한다
-- (PAYMENT_ALREADY_REFUNDED 등 이번 PR에서 추가된 변경은 여기 포함하지 않고 V2 이후로 분리 —
-- 기존 환경은 baseline-on-migrate로 이 파일을 재실행하지 않으므로, 이번 PR의 실제 변경사항은
-- 신규 환경이든 기존 환경이든 동일하게 V2+로 적용받아야 누락 없이 반영된다).
-- 신규/빈 스키마 환경(CI 등)에서만 이 파일이 실제로 실행되어 아래 내용대로 테이블을 만든다.

CREATE TABLE orders.inbox_events (
    id uuid NOT NULL,
    event_id character varying(100) NOT NULL,
    event_type character varying(100) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    error_message character varying(500),
    created_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    CONSTRAINT inbox_events_pkey PRIMARY KEY (id),
    CONSTRAINT uk_inbox_events_event_id UNIQUE (event_id)
);

CREATE TABLE orders.order_histories (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    new_status character varying(30) NOT NULL,
    order_id uuid NOT NULL,
    previous_status character varying(30),
    reason_code character varying(50),
    reason_message character varying(255),
    source_event_key character varying(100),
    CONSTRAINT order_histories_pkey PRIMARY KEY (id),
    CONSTRAINT order_histories_new_status_check CHECK (((new_status)::text = ANY ((ARRAY['PAYMENT_PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'CANCEL_REQUESTED'::character varying, 'REFUND_PENDING'::character varying, 'REFUNDED'::character varying, 'REFUND_FAILED'::character varying])::text[]))),
    CONSTRAINT order_histories_previous_status_check CHECK (((previous_status)::text = ANY ((ARRAY['PAYMENT_PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'CANCEL_REQUESTED'::character varying, 'REFUND_PENDING'::character varying, 'REFUNDED'::character varying, 'REFUND_FAILED'::character varying])::text[])))
);

CREATE INDEX idx_order_histories_order_id ON orders.order_histories USING btree (order_id);
CREATE INDEX idx_order_histories_source_event_key ON orders.order_histories USING btree (source_event_key);

CREATE TABLE orders.order_saga_states (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    saga_id character varying(64) NOT NULL,
    current_step character varying(50) NOT NULL,
    compensating_since timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT order_saga_states_pkey PRIMARY KEY (id),
    CONSTRAINT uk_order_saga_states_order_id UNIQUE (order_id)
);

CREATE TABLE orders.orders (
    id uuid NOT NULL,
    cancelled_at timestamp(6) with time zone,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone NOT NULL,
    deleted_at timestamp(6) with time zone,
    drop_id uuid NOT NULL,
    fail_code character varying(50),
    fail_message character varying(255),
    idempotency_key character varying(100) NOT NULL,
    member_id uuid NOT NULL,
    next_payment_status_check_at timestamp(6) with time zone,
    order_number character varying(30) NOT NULL,
    paid_at timestamp(6) with time zone,
    payment_expires_at timestamp(6) with time zone,
    payment_id uuid,
    payment_status_check_failed_at timestamp(6) with time zone,
    payment_status_check_failure_count integer,
    product_id uuid NOT NULL,
    product_name character varying(255) NOT NULL,
    quantity integer NOT NULL,
    refunded_at timestamp(6) with time zone,
    saga_id character varying(64),
    seller_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    total_price bigint NOT NULL,
    unit_price bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT orders_pkey PRIMARY KEY (id),
    CONSTRAINT uk_orders_member_id_idempotency_key UNIQUE (member_id, idempotency_key),
    CONSTRAINT uk_orders_order_number UNIQUE (order_number),
    CONSTRAINT orders_fail_code_check CHECK (((fail_code)::text = ANY ((ARRAY['SOLD_OUT'::character varying, 'DROP_NOT_OPEN'::character varying, 'DROP_CLOSED'::character varying, 'LIMIT_EXCEEDED'::character varying, 'PAYMENT_FAILED'::character varying, 'PAYMENT_EXPIRED'::character varying, 'PAYMENT_NO_RESPONSE'::character varying, 'PG_ERROR'::character varying, 'PRODUCT_INTEGRATION_FAILED'::character varying, 'REFUND_REQUEST_FAILED'::character varying, 'STOCK_ROLLBACK_FAILED'::character varying])::text[]))),
    CONSTRAINT orders_status_check CHECK (((status)::text = ANY ((ARRAY['PAYMENT_PENDING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'CANCEL_REQUESTED'::character varying, 'REFUND_PENDING'::character varying, 'REFUNDED'::character varying, 'REFUND_FAILED'::character varying])::text[])))
);

CREATE INDEX idx_orders_drop_id ON orders.orders USING btree (drop_id);
CREATE INDEX idx_orders_member_id ON orders.orders USING btree (member_id);
CREATE INDEX idx_orders_saga_id ON orders.orders USING btree (saga_id);
CREATE INDEX idx_orders_status ON orders.orders USING btree (status);
CREATE INDEX idx_orders_status_completed_at ON orders.orders USING btree (status, completed_at);

CREATE TABLE orders.outbox_events (
    id uuid NOT NULL,
    topic character varying(100) NOT NULL,
    payload text NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT outbox_events_pkey PRIMARY KEY (id)
);

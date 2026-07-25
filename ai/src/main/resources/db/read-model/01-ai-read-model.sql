-- psql 변수 AI_QUERY_DB_PASSWORD는 ai/scripts/apply-read-model.sh가 환경 변수에서 주입한다.
-- 이 파일은 원본 도메인 migration이 끝난 뒤 CREATEROLE 권한이 있는 관리 계정으로 적용한다.

DO $role$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'ai_view_owner') THEN
        CREATE ROLE ai_view_owner;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'ai_query_app') THEN
        CREATE ROLE ai_query_app;
    END IF;
END
$role$;

ALTER ROLE ai_view_owner
    NOLOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS;

ALTER ROLE ai_query_app
    LOGIN
    NOINHERIT
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION
    NOBYPASSRLS
    CONNECTION LIMIT 4;
ALTER ROLE ai_query_app PASSWORD :'AI_QUERY_DB_PASSWORD';
ALTER ROLE ai_query_app SET default_transaction_read_only = 'on';
ALTER ROLE ai_query_app SET statement_timeout = '3s';
ALTER ROLE ai_query_app SET lock_timeout = '500ms';

DO $membership$
DECLARE
    granted_role_name text;
BEGIN
    FOR granted_role_name IN
        SELECT granted_role.rolname
        FROM pg_catalog.pg_auth_members membership
        JOIN pg_catalog.pg_roles granted_role ON granted_role.oid = membership.roleid
        JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
        WHERE member_role.rolname = 'ai_query_app'
    LOOP
        EXECUTE format('REVOKE %I FROM ai_query_app', granted_role_name);
    END LOOP;
END
$membership$;

REVOKE ai_query_app FROM ai_view_owner;

CREATE SCHEMA IF NOT EXISTS ai_read AUTHORIZATION ai_view_owner;
ALTER SCHEMA ai_read OWNER TO ai_view_owner;
REVOKE ALL PRIVILEGES ON SCHEMA ai_read FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA ai_read FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA ai_read FROM PUBLIC;

REVOKE ALL PRIVILEGES ON SCHEMA orders FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON SCHEMA member FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON SCHEMA payment FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON SCHEMA product FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON SCHEMA settlement FROM ai_view_owner;

REVOKE ALL PRIVILEGES ON TABLE orders.orders FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE orders.order_histories FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE orders.order_saga_states FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE orders.inbox_events FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE orders.outbox_events FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE member.member FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE member.role FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE member.role_history FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE member.wishlist_item FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE member.outbox_events FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE payment.payments FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE payment.refunds FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE payment.outbox_events FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE product.products FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE product.categories FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE product.product_images FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE product.drops FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE product.stock_histories FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE settlement.settlement_orders FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE settlement.seller_settlements FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE settlement.settlement_batchs FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE settlement.settlement_adjustments FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE settlement.daily_reconciliation_results FROM ai_view_owner;
REVOKE ALL PRIVILEGES ON TABLE settlement.daily_reconciliation_discrepancies FROM ai_view_owner;

GRANT USAGE ON SCHEMA orders, member, payment, product, settlement TO ai_view_owner;
GRANT SELECT (
    id,
    order_number,
    product_id,
    product_name,
    quantity,
    unit_price,
    total_price,
    status,
    fail_code,
    payment_expires_at,
    created_at,
    updated_at,
    paid_at,
    completed_at,
    cancelled_at,
    refunded_at,
    deleted_at
) ON TABLE orders.orders TO ai_view_owner;
GRANT SELECT (
    id,
    order_id,
    previous_status,
    new_status,
    reason_code,
    created_at
) ON TABLE orders.order_histories TO ai_view_owner;
GRANT SELECT (
    order_id,
    current_step,
    compensating_since,
    created_at,
    updated_at
) ON TABLE orders.order_saga_states TO ai_view_owner;
GRANT SELECT (event_type, status, created_at)
    ON TABLE orders.inbox_events TO ai_view_owner;
GRANT SELECT (topic, status, created_at)
    ON TABLE orders.outbox_events TO ai_view_owner;
GRANT SELECT (id, platform_type, created_at, deleted_at)
    ON TABLE member.member TO ai_view_owner;
GRANT SELECT (id, role)
    ON TABLE member.role TO ai_view_owner;
GRANT SELECT (id, member_id, role_id, created_at, deleted_at)
    ON TABLE member.role_history TO ai_view_owner;
GRANT SELECT (product_id)
    ON TABLE member.wishlist_item TO ai_view_owner;
GRANT SELECT (topic, status, created_at)
    ON TABLE member.outbox_events TO ai_view_owner;
GRANT SELECT (
    created_at,
    approved_at,
    status,
    method,
    pg_provider,
    pg_recon_status,
    amount,
    refunded_amount
) ON TABLE payment.payments TO ai_view_owner;
GRANT SELECT (
    created_at,
    completed_at,
    status,
    pg_recon_status,
    amount
) ON TABLE payment.refunds TO ai_view_owner;
GRANT SELECT (topic, status, created_at)
    ON TABLE payment.outbox_events TO ai_view_owner;
GRANT SELECT (
    id,
    category_id,
    name,
    description,
    price,
    thumbnail_key,
    created_at,
    deleted_at
) ON TABLE product.products TO ai_view_owner;
GRANT SELECT (id, name)
    ON TABLE product.categories TO ai_view_owner;
GRANT SELECT (product_id)
    ON TABLE product.product_images TO ai_view_owner;
GRANT SELECT (
    id,
    product_id,
    drop_price,
    total_quantity,
    limit_per_user,
    open_at,
    close_at,
    status,
    created_at,
    deleted_at
) ON TABLE product.drops TO ai_view_owner;
GRANT SELECT (drop_id, change_type, quantity_delta)
    ON TABLE product.stock_histories TO ai_view_owner;
GRANT SELECT (
    product_id,
    settlement_month,
    paid_at,
    settlement_status,
    order_amount,
    paid_amount,
    fee_amount,
    refund_amount,
    net_settlement_amount
) ON TABLE settlement.settlement_orders TO ai_view_owner;
GRANT SELECT (
    settlement_month,
    status,
    total_order_count,
    total_paid_amount,
    total_fee_amount,
    total_refund_amount,
    total_adjustment_amount,
    final_settlement_amount
) ON TABLE settlement.seller_settlements TO ai_view_owner;
GRANT SELECT (
    settlement_month,
    created_at,
    started_at,
    ended_at,
    batch_type,
    status,
    total_order_count,
    total_seller_count,
    total_settlement_amount
) ON TABLE settlement.settlement_batchs TO ai_view_owner;
GRANT SELECT (
    settlement_month,
    adjustment_type,
    status,
    adjustment_amount
) ON TABLE settlement.settlement_adjustments TO ai_view_owner;
GRANT SELECT (
    business_date,
    executed_at,
    status,
    payment_count,
    total_payment_amount,
    refund_count,
    total_refund_amount,
    expected_settlement_amount,
    discrepancy_count
) ON TABLE settlement.daily_reconciliation_results TO ai_view_owner;
GRANT SELECT (business_date, entity_type, discrepancy_type)
    ON TABLE settlement.daily_reconciliation_discrepancies TO ai_view_owner;

REVOKE ALL PRIVILEGES ON SCHEMA orders FROM ai_query_app;
REVOKE ALL PRIVILEGES ON SCHEMA member FROM ai_query_app;
REVOKE ALL PRIVILEGES ON SCHEMA payment FROM ai_query_app;
REVOKE ALL PRIVILEGES ON SCHEMA product FROM ai_query_app;
REVOKE ALL PRIVILEGES ON SCHEMA settlement FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE orders.orders FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE orders.order_histories FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE orders.order_saga_states FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE orders.inbox_events FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE orders.outbox_events FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE member.member FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE member.role FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE member.role_history FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE member.wishlist_item FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE member.outbox_events FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE payment.payments FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE payment.refunds FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE payment.outbox_events FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE product.products FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE product.categories FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE product.product_images FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE product.drops FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE product.stock_histories FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE settlement.settlement_orders FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE settlement.seller_settlements FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE settlement.settlement_batchs FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE settlement.settlement_adjustments FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE settlement.daily_reconciliation_results FROM ai_query_app;
REVOKE ALL PRIVILEGES ON TABLE settlement.daily_reconciliation_discrepancies FROM ai_query_app;

DROP FUNCTION IF EXISTS ai_read.lookup_order_detail(character varying);
DROP FUNCTION IF EXISTS ai_read.lookup_order_process_events(character varying);
DROP FUNCTION IF EXISTS ai_read.lookup_order_current_saga(character varying);

DROP VIEW IF EXISTS ai_read.v_event_pipeline_analytics;
DROP VIEW IF EXISTS ai_read.v_member_registration_analytics;
DROP VIEW IF EXISTS ai_read.v_order_saga_analytics;
DROP VIEW IF EXISTS ai_read.v_payment_analytics;
DROP VIEW IF EXISTS ai_read.v_refund_analytics;
DROP VIEW IF EXISTS ai_read.v_settlement_order_analytics;
DROP VIEW IF EXISTS ai_read.v_seller_settlement_analytics;
DROP VIEW IF EXISTS ai_read.v_settlement_batch_analytics;
DROP VIEW IF EXISTS ai_read.v_settlement_adjustment_analytics;
DROP VIEW IF EXISTS ai_read.v_reconciliation_analytics;
DROP VIEW IF EXISTS ai_read.v_reconciliation_discrepancy_analytics;
DROP VIEW IF EXISTS ai_read.v_drop_analytics;
DROP VIEW IF EXISTS ai_read.v_member_current_snapshot;
DROP VIEW IF EXISTS ai_read.v_order_analytics;
DROP VIEW IF EXISTS ai_read.v_order_current_saga;
DROP VIEW IF EXISTS ai_read.v_order_details;
DROP VIEW IF EXISTS ai_read.v_order_process_events;
DROP VIEW IF EXISTS ai_read.v_order_status_transitions;
DROP VIEW IF EXISTS ai_read.v_payment_pending_expirations;
DROP VIEW IF EXISTS ai_read.v_product_analytics;

SET ROLE ai_view_owner;

CREATE VIEW ai_read.v_order_analytics
WITH (security_barrier = true)
AS
SELECT
    source_order.created_at,
    source_order.paid_at,
    source_order.completed_at,
    source_order.cancelled_at,
    source_order.refunded_at,
    source_order.status,
    source_order.fail_code,
    source_order.product_name,
    COALESCE(category.name, 'UNCLASSIFIED') AS category_name,
    source_order.quantity,
    source_order.unit_price,
    source_order.total_price,
    source_order.order_number
FROM orders.orders source_order
LEFT JOIN product.products source_product
  ON source_product.id = source_order.product_id
LEFT JOIN product.categories category
  ON category.id = source_product.category_id
WHERE source_order.deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_order_analytics IS
    '삭제되지 않은 주문의 판매 분석 fact. 공개 주문번호와 비민감 상품·가격 스냅샷만 제공하고 회원·판매자·내부 ID와 자유 실패 사유는 제외';

CREATE VIEW ai_read.v_payment_pending_expirations
WITH (security_barrier = true)
AS
SELECT
    payment_expires_at
FROM orders.orders
WHERE deleted_at IS NULL
  AND status = 'PAYMENT_PENDING'
  AND payment_expires_at IS NOT NULL;

COMMENT ON VIEW ai_read.v_payment_pending_expirations IS
    '현재 PAYMENT_PENDING인 주문의 결제 기한 경과 건수 집계 전용 뷰. 스케줄러 재처리 가능 조건은 포함하지 않음';

CREATE VIEW ai_read.v_order_status_transitions
WITH (security_barrier = true)
AS
SELECT
    history.created_at AS occurred_at,
    history.previous_status,
    history.new_status,
    history.reason_code
FROM orders.order_histories history
JOIN orders.orders source_order
  ON source_order.id = history.order_id
WHERE source_order.deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_order_status_transitions IS
    '삭제되지 않은 주문의 비식별 상태 전이 집계 fact. 자유 사유와 원천 이벤트 키는 제외';

CREATE VIEW ai_read.v_order_details
WITH (security_barrier = true)
AS
SELECT
    order_number,
    product_name,
    quantity,
    status,
    fail_code,
    payment_expires_at,
    created_at,
    updated_at,
    paid_at,
    completed_at,
    cancelled_at,
    refunded_at,
    unit_price,
    total_price
FROM orders.orders
WHERE deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_order_details IS
    '외부 주문번호 exact lookup용 최소 주문 스냅샷. 주문 당시 단가·총액은 제공하고 내부 UUID, 회원, 판매자와 자유 실패 사유는 제외';

CREATE VIEW ai_read.v_order_process_events
WITH (security_barrier = true)
AS
SELECT
    source_order.order_number,
    row_number() OVER (
        PARTITION BY history.order_id
        ORDER BY history.created_at, history.id
    ) AS event_sequence,
    history.created_at AS occurred_at,
    history.previous_status,
    history.new_status,
    history.reason_code
FROM orders.order_histories history
JOIN orders.orders source_order
  ON source_order.id = history.order_id
WHERE source_order.deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_order_process_events IS
    '외부 주문번호 exact lookup용 상태 처리 이벤트. 내부 UUID, 자유 사유와 원천 이벤트 키는 제외';

CREATE VIEW ai_read.v_order_current_saga
WITH (security_barrier = true)
AS
SELECT
    source_order.order_number,
    saga.current_step,
    saga.compensating_since,
    saga.created_at AS saga_created_at,
    saga.updated_at AS saga_updated_at
FROM orders.orders source_order
JOIN orders.order_saga_states saga
  ON saga.order_id = source_order.id
WHERE source_order.deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_order_current_saga IS
    '외부 주문번호 exact lookup용 현재 사가 스냅샷. 내부 order/saga UUID와 saga 식별자는 제외';

CREATE VIEW ai_read.v_order_saga_analytics
WITH (security_barrier = true)
AS
SELECT
    saga.current_step,
    saga.compensating_since,
    saga.created_at AS saga_created_at,
    saga.updated_at AS saga_updated_at
FROM orders.order_saga_states saga
JOIN orders.orders source_order
  ON source_order.id = saga.order_id
WHERE source_order.deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_order_saga_analytics IS
    '삭제되지 않은 주문의 현재 사가 단계와 보상 지연 집계용 비식별 fact. 주문번호와 모든 내부 식별자는 제외';

CREATE VIEW ai_read.v_member_current_snapshot
WITH (security_barrier = true)
AS
WITH active_roles AS (
    SELECT
        history.member_id,
        role.role::text AS member_role,
        row_number() OVER (
            PARTITION BY history.member_id
            ORDER BY history.created_at DESC, history.id DESC
        ) AS role_order
    FROM member.role_history history
    JOIN member.role role
      ON role.id = history.role_id
    WHERE history.deleted_at IS NULL
)
SELECT
    source_member.platform_type::text AS platform_type,
    COALESCE(active_role.member_role, 'UNASSIGNED') AS member_role,
    count(*)::bigint AS member_count
FROM member.member source_member
LEFT JOIN active_roles active_role
  ON active_role.member_id = source_member.id
 AND active_role.role_order = 1
WHERE source_member.deleted_at IS NULL
GROUP BY
    source_member.platform_type::text,
    COALESCE(active_role.member_role, 'UNASSIGNED');

COMMENT ON VIEW ai_read.v_member_current_snapshot IS
    '탈퇴하지 않은 회원을 가입 경로와 현재 유효 역할로 선집계한 snapshot. 개인 단위 행과 식별정보는 제공하지 않음';

CREATE VIEW ai_read.v_member_registration_analytics
WITH (security_barrier = true)
AS
WITH member_events AS (
    SELECT
        source_member.created_at AS occurred_at,
        source_member.platform_type::text AS platform_type,
        1::bigint AS new_member_count,
        0::bigint AS withdrawn_member_count
    FROM member.member source_member

    UNION ALL

    SELECT
        source_member.deleted_at AS occurred_at,
        source_member.platform_type::text AS platform_type,
        0::bigint AS new_member_count,
        1::bigint AS withdrawn_member_count
    FROM member.member source_member
    WHERE source_member.deleted_at IS NOT NULL
)
SELECT
    date_trunc('day', occurred_at) AT TIME ZONE 'Asia/Seoul' AS period_start,
    platform_type,
    sum(new_member_count)::bigint AS new_member_count,
    sum(withdrawn_member_count)::bigint AS withdrawn_member_count
FROM member_events
GROUP BY date_trunc('day', occurred_at), platform_type;

COMMENT ON VIEW ai_read.v_member_registration_analytics IS
    'KST 일자와 가입 플랫폼별 가입·탈퇴 회원 수 선집계. 회원 ID, 이메일, 닉네임 등 개인 단위 정보는 제공하지 않음';

CREATE VIEW ai_read.v_product_analytics
WITH (security_barrier = true)
AS
WITH wishlist_totals AS (
    SELECT
        wishlist.product_id,
        count(*)::bigint AS wishlist_count
    FROM member.wishlist_item wishlist
    GROUP BY wishlist.product_id
)
SELECT
    source_product.created_at,
    source_product.deleted_at,
    COALESCE(source_product.name, 'UNKNOWN_PRODUCT') AS product_name,
    COALESCE(category.name, 'UNCLASSIFIED') AS category_name,
    source_product.price,
    source_product.price IS NOT NULL AS price_configured,
    NULLIF(btrim(source_product.description), '') IS NOT NULL AS description_configured,
    (
        NULLIF(btrim(source_product.thumbnail_key), '') IS NOT NULL
        OR EXISTS (
            SELECT 1
            FROM product.product_images product_image
            WHERE product_image.product_id = source_product.id
        )
    ) AS image_configured,
    COALESCE(wishlist_total.wishlist_count, 0)::bigint AS wishlist_count,
    source_product.id AS product_id
FROM product.products source_product
LEFT JOIN product.categories category
  ON category.id = source_product.category_id
LEFT JOIN wishlist_totals wishlist_total
  ON wishlist_total.product_id = source_product.id;

COMMENT ON VIEW ai_read.v_product_analytics IS
    '공개 상품 ID·상품명·가격·현재 찜 수와 운영 완성도 분석 fact. deleted_at으로 생명주기를 구분하며 판매자 식별자와 이미지 키는 제외';

CREATE VIEW ai_read.v_drop_analytics
WITH (security_barrier = true)
AS
WITH stock_totals AS (
    SELECT
        stock.drop_id,
        sum(stock.quantity_delta)::bigint AS quantity_delta,
        sum(
            CASE
                WHEN stock.change_type::text = 'DEDUCT'
                    THEN -stock.quantity_delta
                ELSE 0
            END
        )::bigint AS deducted_quantity,
        sum(
            CASE
                WHEN stock.change_type::text = 'ROLLBACK'
                    THEN stock.quantity_delta
                ELSE 0
            END
        )::bigint AS rollback_quantity,
        count(*)::bigint AS stock_change_count,
        count(*) FILTER (
            WHERE stock.change_type::text = 'ROLLBACK'
        )::bigint AS rollback_count
    FROM product.stock_histories stock
    GROUP BY stock.drop_id
)
SELECT
    source_drop.created_at,
    source_drop.deleted_at,
    source_drop.open_at,
    source_drop.close_at,
    source_drop.status AS persisted_status,
    CASE
        WHEN source_drop.status::text = 'CLOSE'
            THEN 'CLOSE'
        WHEN CURRENT_TIMESTAMP < source_drop.open_at
            THEN 'REGISTERED'
        WHEN source_drop.status::text = 'REGISTERED'
          AND CURRENT_TIMESTAMP >= source_drop.open_at
          AND (
              source_drop.close_at IS NULL
              OR CURRENT_TIMESTAMP < source_drop.close_at
          )
            THEN CASE
                WHEN (
                    source_drop.total_quantity::bigint
                    + COALESCE(stock_total.quantity_delta, 0)
                ) > 0
                    THEN 'OPEN'
                ELSE 'SOLD_OUT'
            END
        ELSE 'CLOSE'
    END AS current_status,
    COALESCE(category.name, 'UNCLASSIFIED') AS category_name,
    CASE
        WHEN (
            source_drop.total_quantity::bigint
            + COALESCE(stock_total.quantity_delta, 0)
        ) < 0
          OR (
            source_drop.total_quantity::bigint
            + COALESCE(stock_total.quantity_delta, 0)
        ) > source_drop.total_quantity::bigint
            THEN 'INVALID'
        WHEN (
            source_drop.total_quantity::bigint
            + COALESCE(stock_total.quantity_delta, 0)
        ) = 0
            THEN 'SOLD_OUT'
        ELSE 'AVAILABLE'
    END AS inventory_state,
    source_drop.close_at IS NOT NULL AS scheduled_close_configured,
    source_drop.limit_per_user IS NOT NULL AS user_limit_configured,
    source_product.name AS product_name,
    source_drop.drop_price,
    source_drop.total_quantity,
    (
        source_drop.total_quantity::bigint
        + COALESCE(stock_total.quantity_delta, 0)
    )::bigint AS remaining_quantity,
    COALESCE(stock_total.deducted_quantity, 0)::bigint AS deducted_quantity,
    COALESCE(stock_total.rollback_quantity, 0)::bigint AS rollback_quantity,
    COALESCE(stock_total.stock_change_count, 0)::bigint AS stock_change_count,
    COALESCE(stock_total.rollback_count, 0)::bigint AS rollback_count,
    source_drop.id AS drop_id
FROM product.drops source_drop
JOIN product.products source_product
  ON source_product.id = source_drop.product_id
LEFT JOIN product.categories category
  ON category.id = source_product.category_id
LEFT JOIN stock_totals stock_total
  ON stock_total.drop_id = source_drop.id
WHERE source_drop.deleted_at IS NULL
  AND source_product.deleted_at IS NULL;

COMMENT ON VIEW ai_read.v_drop_analytics IS
    '삭제되지 않은 드롭의 공개 ID·상품명·가격·재고 흐름 분석 fact. 상품·판매자·주문 ID는 제외';

CREATE VIEW ai_read.v_payment_analytics
WITH (security_barrier = true)
AS
SELECT
    source_payment.created_at AT TIME ZONE 'Asia/Seoul' AS created_at,
    source_payment.approved_at AT TIME ZONE 'Asia/Seoul' AS approved_at,
    source_payment.status::text AS status,
    source_payment.method::text AS method,
    source_payment.pg_provider,
    source_payment.pg_recon_status::text AS pg_recon_status,
    source_payment.amount,
    source_payment.refunded_amount
FROM payment.payments source_payment;

COMMENT ON VIEW ai_read.v_payment_analytics IS
    '결제 시점·상태·수단·PG 대사·금액 분석용 비식별 fact. 모든 ID, 결제 키, 거래 키, 멱등 키와 해시는 제외';

CREATE VIEW ai_read.v_refund_analytics
WITH (security_barrier = true)
AS
SELECT
    source_refund.created_at AT TIME ZONE 'Asia/Seoul' AS created_at,
    source_refund.completed_at AT TIME ZONE 'Asia/Seoul' AS completed_at,
    source_refund.status::text AS status,
    source_refund.pg_recon_status::text AS pg_recon_status,
    source_refund.amount
FROM payment.refunds source_refund;

COMMENT ON VIEW ai_read.v_refund_analytics IS
    '환불 시점·상태·PG 대사·금액 분석용 비식별 fact. 모든 ID, 환불 키, 멱등 키, 해시와 자유 사유는 제외';

CREATE VIEW ai_read.v_settlement_order_analytics
WITH (security_barrier = true)
AS
SELECT
    make_timestamptz(
        substring(settlement_order.settlement_month, 1, 4)::integer,
        substring(settlement_order.settlement_month, 5, 2)::integer,
        1,
        0,
        0,
        0,
        'Asia/Seoul'
    ) AS period_start,
    settlement_order.paid_at AT TIME ZONE 'Asia/Seoul' AS paid_at,
    settlement_order.settlement_status::text AS settlement_status,
    source_product.name AS product_name,
    COALESCE(category.name, 'UNCLASSIFIED') AS category_name,
    settlement_order.order_amount,
    settlement_order.paid_amount,
    settlement_order.fee_amount,
    settlement_order.refund_amount,
    settlement_order.net_settlement_amount
FROM settlement.settlement_orders settlement_order
LEFT JOIN product.products source_product
  ON source_product.id = settlement_order.product_id
LEFT JOIN product.categories category
  ON category.id = source_product.category_id;

COMMENT ON VIEW ai_read.v_settlement_order_analytics IS
    '정산 주문의 월·결제 시점·상태·금액 분석용 비식별 fact. 상품명과 category_name은 정산 당시 스냅샷이 아니라 현재 상품 정보이며 모든 주체·거래 ID는 제외';

CREATE VIEW ai_read.v_seller_settlement_analytics
WITH (security_barrier = true)
AS
SELECT
    make_timestamptz(
        substring(seller_settlement.settlement_month, 1, 4)::integer,
        substring(seller_settlement.settlement_month, 5, 2)::integer,
        1,
        0,
        0,
        0,
        'Asia/Seoul'
    ) AS period_start,
    seller_settlement.status::text AS settlement_status,
    count(*)::bigint AS seller_count,
    sum(seller_settlement.total_order_count)::bigint AS total_order_count,
    sum(seller_settlement.total_paid_amount)::bigint AS total_paid_amount,
    sum(seller_settlement.total_fee_amount)::bigint AS total_fee_amount,
    sum(seller_settlement.total_refund_amount)::bigint AS total_refund_amount,
    sum(seller_settlement.total_adjustment_amount)::bigint AS total_adjustment_amount,
    sum(seller_settlement.final_settlement_amount)::bigint AS final_settlement_amount
FROM settlement.seller_settlements seller_settlement
GROUP BY seller_settlement.settlement_month, seller_settlement.status::text;

COMMENT ON VIEW ai_read.v_seller_settlement_analytics IS
    '월·정산 상태별 판매자 정산 사전 집계. 판매자 ID와 실패 사유를 제외해 개별 판매자 조회를 허용하지 않음';

CREATE VIEW ai_read.v_settlement_batch_analytics
WITH (security_barrier = true)
AS
SELECT
    make_timestamptz(
        substring(settlement_batch.settlement_month, 1, 4)::integer,
        substring(settlement_batch.settlement_month, 5, 2)::integer,
        1,
        0,
        0,
        0,
        'Asia/Seoul'
    ) AS period_start,
    settlement_batch.created_at AT TIME ZONE 'Asia/Seoul' AS created_at,
    settlement_batch.started_at AT TIME ZONE 'Asia/Seoul' AS started_at,
    settlement_batch.ended_at AT TIME ZONE 'Asia/Seoul' AS ended_at,
    settlement_batch.batch_type::text AS batch_type,
    settlement_batch.status::text AS status,
    settlement_batch.total_order_count,
    settlement_batch.total_seller_count,
    settlement_batch.total_settlement_amount
FROM settlement.settlement_batchs settlement_batch;

COMMENT ON VIEW ai_read.v_settlement_batch_analytics IS
    '정산 배치 실행 현황 분석용 비식별 fact. 배치 ID와 자유 실패 사유는 제외';

CREATE VIEW ai_read.v_settlement_adjustment_analytics
WITH (security_barrier = true)
AS
SELECT
    make_timestamptz(
        substring(settlement_adjustment.settlement_month, 1, 4)::integer,
        substring(settlement_adjustment.settlement_month, 5, 2)::integer,
        1,
        0,
        0,
        0,
        'Asia/Seoul'
    ) AS period_start,
    settlement_adjustment.adjustment_type::text AS adjustment_type,
    settlement_adjustment.status::text AS status,
    count(*)::bigint AS adjustment_count,
    sum(settlement_adjustment.adjustment_amount)::bigint AS adjustment_amount
FROM settlement.settlement_adjustments settlement_adjustment
GROUP BY
    settlement_adjustment.settlement_month,
    settlement_adjustment.adjustment_type::text,
    settlement_adjustment.status::text;

COMMENT ON VIEW ai_read.v_settlement_adjustment_analytics IS
    '월·보정 유형·상태별 정산 보정 사전 집계. 판매자·주문·환불 ID와 자유 사유는 제외';

CREATE VIEW ai_read.v_reconciliation_analytics
WITH (security_barrier = true)
AS
SELECT
    reconciliation.business_date::timestamp AT TIME ZONE 'Asia/Seoul' AS period_start,
    reconciliation.executed_at AT TIME ZONE 'Asia/Seoul' AS executed_at,
    reconciliation.status::text AS status,
    reconciliation.payment_count,
    reconciliation.total_payment_amount,
    reconciliation.refund_count,
    reconciliation.total_refund_amount,
    reconciliation.expected_settlement_amount,
    reconciliation.discrepancy_count
FROM settlement.daily_reconciliation_results reconciliation;

COMMENT ON VIEW ai_read.v_reconciliation_analytics IS
    'KST 영업일별 결제·환불·예상 정산액 대사 결과. 실행 식별자나 상세 원문은 제외';

CREATE VIEW ai_read.v_reconciliation_discrepancy_analytics
WITH (security_barrier = true)
AS
SELECT
    discrepancy.business_date::timestamp AT TIME ZONE 'Asia/Seoul' AS period_start,
    discrepancy.entity_type::text AS entity_type,
    discrepancy.discrepancy_type::text AS discrepancy_type,
    count(*)::bigint AS discrepancy_count
FROM settlement.daily_reconciliation_discrepancies discrepancy
GROUP BY
    discrepancy.business_date,
    discrepancy.entity_type::text,
    discrepancy.discrepancy_type::text;

COMMENT ON VIEW ai_read.v_reconciliation_discrepancy_analytics IS
    'KST 영업일·대상 유형·불일치 유형별 정산 대사 불일치 사전 집계. 참조 ID와 상세 원문은 제외';

CREATE VIEW ai_read.v_event_pipeline_analytics
WITH (security_barrier = true)
AS
WITH pipeline_events AS (
    SELECT
        date_trunc('hour', inbox.created_at AT TIME ZONE 'Asia/Seoul')
            AT TIME ZONE 'Asia/Seoul' AS bucket_start,
        'orders'::text AS service_name,
        'INBOX'::text AS direction,
        inbox.event_type::text AS event_type,
        inbox.status::text AS status,
        inbox.created_at AS event_at
    FROM orders.inbox_events inbox

    UNION ALL

    SELECT
        date_trunc('hour', outbox.created_at AT TIME ZONE 'Asia/Seoul')
            AT TIME ZONE 'Asia/Seoul' AS bucket_start,
        'orders'::text AS service_name,
        'OUTBOX'::text AS direction,
        outbox.topic::text AS event_type,
        outbox.status::text AS status,
        outbox.created_at AS event_at
    FROM orders.outbox_events outbox

    UNION ALL

    SELECT
        date_trunc('hour', outbox.created_at) AT TIME ZONE 'Asia/Seoul' AS bucket_start,
        'payment'::text AS service_name,
        'OUTBOX'::text AS direction,
        outbox.topic::text AS event_type,
        outbox.status::text AS status,
        outbox.created_at AT TIME ZONE 'Asia/Seoul' AS event_at
    FROM payment.outbox_events outbox

    UNION ALL

    SELECT
        date_trunc('hour', outbox.created_at) AT TIME ZONE 'Asia/Seoul' AS bucket_start,
        'member'::text AS service_name,
        'OUTBOX'::text AS direction,
        outbox.topic::text AS event_type,
        outbox.status::text AS status,
        outbox.created_at AT TIME ZONE 'Asia/Seoul' AS event_at
    FROM member.outbox_events outbox
)
SELECT
    bucket_start,
    service_name,
    direction,
    event_type,
    status,
    min(event_at) AS oldest_event_at,
    count(*)::bigint AS event_count
FROM pipeline_events
GROUP BY bucket_start, service_name, direction, event_type, status;

COMMENT ON VIEW ai_read.v_event_pipeline_analytics IS
    'KST 시간·서비스·방향·이벤트 종류·상태별 inbox/outbox 사전 집계. aggregate ID, payload와 오류 원문은 제외';

CREATE FUNCTION ai_read.lookup_order_detail(p_order_number character varying)
RETURNS TABLE (
    order_number character varying,
    product_name character varying,
    quantity integer,
    status character varying,
    fail_code character varying,
    payment_expires_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone,
    paid_at timestamp with time zone,
    completed_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    refunded_at timestamp with time zone,
    unit_price bigint,
    total_price bigint
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, ai_read
AS $function$
    SELECT
        detail.order_number,
        detail.product_name,
        detail.quantity,
        detail.status,
        detail.fail_code,
        detail.payment_expires_at,
        detail.created_at,
        detail.updated_at,
        detail.paid_at,
        detail.completed_at,
        detail.cancelled_at,
        detail.refunded_at,
        detail.unit_price,
        detail.total_price
    FROM orders.orders detail
    WHERE p_order_number IS NOT NULL
      AND char_length(p_order_number) BETWEEN 1 AND 30
      AND left(p_order_number, 4) = 'ORD-'
      AND detail.order_number = p_order_number
      AND detail.deleted_at IS NULL
    LIMIT 1
$function$;

CREATE FUNCTION ai_read.lookup_order_process_events(p_order_number character varying)
RETURNS TABLE (
    event_sequence bigint,
    occurred_at timestamp with time zone,
    previous_status character varying,
    new_status character varying,
    reason_code character varying
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, ai_read
AS $function$
    SELECT
        row_number() OVER (
            ORDER BY history.created_at, history.id
        ) AS event_sequence,
        history.created_at AS occurred_at,
        history.previous_status,
        history.new_status,
        history.reason_code
    FROM orders.orders source_order
    JOIN orders.order_histories history
      ON history.order_id = source_order.id
    WHERE p_order_number IS NOT NULL
      AND char_length(p_order_number) BETWEEN 1 AND 30
      AND left(p_order_number, 4) = 'ORD-'
      AND source_order.order_number = p_order_number
      AND source_order.deleted_at IS NULL
    ORDER BY history.created_at, history.id
    LIMIT 101
$function$;

CREATE FUNCTION ai_read.lookup_order_current_saga(p_order_number character varying)
RETURNS TABLE (
    current_step character varying,
    compensating_since timestamp with time zone,
    saga_created_at timestamp with time zone,
    saga_updated_at timestamp with time zone
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, ai_read
AS $function$
    SELECT
        saga.current_step,
        saga.compensating_since,
        saga.created_at AS saga_created_at,
        saga.updated_at AS saga_updated_at
    FROM orders.orders source_order
    JOIN orders.order_saga_states saga
      ON saga.order_id = source_order.id
    WHERE p_order_number IS NOT NULL
      AND char_length(p_order_number) BETWEEN 1 AND 30
      AND left(p_order_number, 4) = 'ORD-'
      AND source_order.order_number = p_order_number
      AND source_order.deleted_at IS NULL
    LIMIT 1
$function$;

RESET ROLE;

REVOKE ALL PRIVILEGES ON SCHEMA ai_read FROM ai_query_app;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA ai_read FROM ai_query_app;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA ai_read FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA ai_read FROM ai_query_app;
GRANT USAGE ON SCHEMA ai_read TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_drop_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_event_pipeline_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_member_current_snapshot TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_member_registration_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_order_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_order_saga_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_order_status_transitions TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_payment_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_payment_pending_expirations TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_product_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_reconciliation_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_reconciliation_discrepancy_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_refund_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_seller_settlement_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_settlement_adjustment_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_settlement_batch_analytics TO ai_query_app;
GRANT SELECT ON TABLE ai_read.v_settlement_order_analytics TO ai_query_app;
GRANT EXECUTE ON FUNCTION ai_read.lookup_order_detail(character varying) TO ai_query_app;
GRANT EXECUTE ON FUNCTION ai_read.lookup_order_process_events(character varying) TO ai_query_app;
GRANT EXECUTE ON FUNCTION ai_read.lookup_order_current_saga(character varying) TO ai_query_app;

ALTER DEFAULT PRIVILEGES FOR ROLE ai_view_owner IN SCHEMA ai_read
    REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE ai_view_owner IN SCHEMA ai_read
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- 비밀번호 변경을 commit하기 전에 실행 역할로 전환해 구조·유효 권한을 같은 transaction에서
-- 검증한다. 여기서 하나라도 실패하면 view와 비밀번호 변경이 함께 rollback된다.
SET ROLE ai_query_app;

SELECT 1 / (((
    SELECT NOT role.rolsuper
       AND NOT role.rolinherit
       AND NOT role.rolcreaterole
       AND NOT role.rolcreatedb
       AND NOT role.rolreplication
       AND NOT role.rolbypassrls
       AND role.rolconnlimit = 4
    FROM pg_catalog.pg_roles role
    WHERE role.rolname = current_user
))::integer) AS preflight_role_attributes;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_auth_members membership
    JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
    WHERE member_role.rolname = current_user
))::integer) AS preflight_no_role_membership;
SELECT 1 / ((has_schema_privilege(current_user, 'ai_read', 'USAGE'))::integer)
    AS preflight_read_schema_visible;
SELECT 1 / ((NOT has_schema_privilege(current_user, 'ai_read', 'CREATE'))::integer)
    AS preflight_read_schema_not_writable;
SELECT 1 / ((NOT (
    has_schema_privilege(current_user, 'orders', 'USAGE')
    OR has_schema_privilege(current_user, 'member', 'USAGE')
    OR has_schema_privilege(current_user, 'payment', 'USAGE')
    OR has_schema_privilege(current_user, 'product', 'USAGE')
    OR has_schema_privilege(current_user, 'settlement', 'USAGE')
))::integer) AS preflight_source_schemas_hidden;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class source_table
    JOIN pg_catalog.pg_namespace source_schema
      ON source_schema.oid = source_table.relnamespace
    WHERE source_schema.nspname IN ('orders', 'member', 'payment', 'product', 'settlement')
      AND source_table.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND has_any_column_privilege(current_user, source_table.oid, 'SELECT')
))::integer) AS preflight_source_columns_hidden;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_namespace candidate_schema
    WHERE candidate_schema.nspname NOT IN ('pg_catalog', 'information_schema')
      AND candidate_schema.nspname NOT LIKE 'pg_toast%'
      AND candidate_schema.nspname NOT LIKE 'pg_temp_%'
      AND has_schema_privilege(current_user, candidate_schema.oid, 'CREATE')
))::integer) AS preflight_no_application_schema_write;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class candidate_relation
    JOIN pg_catalog.pg_namespace candidate_schema
      ON candidate_schema.oid = candidate_relation.relnamespace
    WHERE candidate_schema.nspname NOT IN ('pg_catalog', 'information_schema')
      AND candidate_schema.nspname NOT LIKE 'pg_toast%'
      AND candidate_schema.nspname NOT LIKE 'pg_temp_%'
      AND candidate_relation.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND NOT (
          candidate_schema.nspname = 'ai_read'
          AND candidate_relation.relname IN (
              'v_drop_analytics',
              'v_event_pipeline_analytics',
              'v_member_current_snapshot',
              'v_member_registration_analytics',
              'v_order_analytics',
              'v_order_saga_analytics',
              'v_order_status_transitions',
              'v_payment_analytics',
              'v_payment_pending_expirations',
              'v_product_analytics',
              'v_reconciliation_analytics',
              'v_reconciliation_discrepancy_analytics',
              'v_refund_analytics',
              'v_seller_settlement_analytics',
              'v_settlement_adjustment_analytics',
              'v_settlement_batch_analytics',
              'v_settlement_order_analytics'
          )
      )
      AND (
          has_table_privilege(current_user, candidate_relation.oid, 'SELECT')
          OR has_table_privilege(current_user, candidate_relation.oid, 'INSERT')
          OR has_table_privilege(current_user, candidate_relation.oid, 'UPDATE')
          OR has_table_privilege(current_user, candidate_relation.oid, 'DELETE')
          OR has_table_privilege(current_user, candidate_relation.oid, 'TRUNCATE')
          OR has_table_privilege(current_user, candidate_relation.oid, 'REFERENCES')
          OR has_table_privilege(current_user, candidate_relation.oid, 'TRIGGER')
          OR has_any_column_privilege(current_user, candidate_relation.oid, 'SELECT')
          OR has_any_column_privilege(current_user, candidate_relation.oid, 'INSERT')
          OR has_any_column_privilege(current_user, candidate_relation.oid, 'UPDATE')
          OR has_any_column_privilege(current_user, candidate_relation.oid, 'REFERENCES')
      )
))::integer) AS preflight_no_unapproved_relation_access;
SELECT 1 / (((
    ARRAY(
        SELECT (table_schema || '.' || table_name || '.' || privilege_type)::text
        FROM information_schema.role_table_grants
        WHERE grantee = current_user
          AND table_schema NOT IN ('pg_catalog', 'information_schema')
        ORDER BY table_schema, table_name, privilege_type
    ) = ARRAY[
        'ai_read.v_drop_analytics.SELECT',
        'ai_read.v_event_pipeline_analytics.SELECT',
        'ai_read.v_member_current_snapshot.SELECT',
        'ai_read.v_member_registration_analytics.SELECT',
        'ai_read.v_order_analytics.SELECT',
        'ai_read.v_order_saga_analytics.SELECT',
        'ai_read.v_order_status_transitions.SELECT',
        'ai_read.v_payment_analytics.SELECT',
        'ai_read.v_payment_pending_expirations.SELECT',
        'ai_read.v_product_analytics.SELECT',
        'ai_read.v_reconciliation_analytics.SELECT',
        'ai_read.v_reconciliation_discrepancy_analytics.SELECT',
        'ai_read.v_refund_analytics.SELECT',
        'ai_read.v_seller_settlement_analytics.SELECT',
        'ai_read.v_settlement_adjustment_analytics.SELECT',
        'ai_read.v_settlement_batch_analytics.SELECT',
        'ai_read.v_settlement_order_analytics.SELECT'
    ]
))::integer) AS preflight_exact_direct_relation_grants;
SELECT 1 / (((
    ARRAY(
        SELECT (routine_schema || '.' || routine_name || '.' || privilege_type)::text
        FROM information_schema.role_routine_grants
        WHERE grantee = current_user
          AND routine_schema = 'ai_read'
        ORDER BY routine_schema, routine_name, privilege_type
    ) = ARRAY[
        'ai_read.lookup_order_current_saga.EXECUTE',
        'ai_read.lookup_order_detail.EXECUTE',
        'ai_read.lookup_order_process_events.EXECUTE'
    ]
))::integer) AS preflight_exact_direct_routine_grants;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_attribute candidate_column
    CROSS JOIN LATERAL pg_catalog.aclexplode(candidate_column.attacl) column_acl
    WHERE column_acl.grantee = (
        SELECT role.oid
        FROM pg_catalog.pg_roles role
        WHERE role.rolname = current_user
    )
))::integer) AS preflight_no_direct_column_grants;
SELECT 1 / (((
    ARRAY(
        SELECT view.viewname::text
        FROM pg_catalog.pg_views view
        WHERE view.schemaname = 'ai_read'
          AND view.viewname IN (
              'v_drop_analytics',
              'v_event_pipeline_analytics',
              'v_member_current_snapshot',
              'v_member_registration_analytics',
              'v_order_analytics',
              'v_order_current_saga',
              'v_order_details',
              'v_order_process_events',
              'v_order_saga_analytics',
              'v_order_status_transitions',
              'v_payment_analytics',
              'v_payment_pending_expirations',
              'v_product_analytics',
              'v_reconciliation_analytics',
              'v_reconciliation_discrepancy_analytics',
              'v_refund_analytics',
              'v_seller_settlement_analytics',
              'v_settlement_adjustment_analytics',
              'v_settlement_batch_analytics',
              'v_settlement_order_analytics'
          )
        ORDER BY view.viewname
    ) = ARRAY[
        'v_drop_analytics',
        'v_event_pipeline_analytics',
        'v_member_current_snapshot',
        'v_member_registration_analytics',
        'v_order_analytics',
        'v_order_current_saga',
        'v_order_details',
        'v_order_process_events',
        'v_order_saga_analytics',
        'v_order_status_transitions',
        'v_payment_analytics',
        'v_payment_pending_expirations',
        'v_product_analytics',
        'v_reconciliation_analytics',
        'v_reconciliation_discrepancy_analytics',
        'v_refund_analytics',
        'v_seller_settlement_analytics',
        'v_settlement_adjustment_analytics',
        'v_settlement_batch_analytics',
        'v_settlement_order_analytics'
    ]
))::integer) AS preflight_exact_managed_view_set;
SELECT 1 / (((
    ARRAY(
        SELECT (relation.relname || '.' || attribute.attname)::text
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace schema
          ON schema.oid = relation.relnamespace
        JOIN pg_catalog.pg_attribute attribute
          ON attribute.attrelid = relation.oid
        WHERE schema.nspname = 'ai_read'
          AND relation.relkind = 'v'
          AND relation.relname IN (
              'v_drop_analytics',
              'v_event_pipeline_analytics',
              'v_member_current_snapshot',
              'v_member_registration_analytics',
              'v_order_analytics',
              'v_order_current_saga',
              'v_order_details',
              'v_order_process_events',
              'v_order_saga_analytics',
              'v_order_status_transitions',
              'v_payment_analytics',
              'v_payment_pending_expirations',
              'v_product_analytics',
              'v_reconciliation_analytics',
              'v_reconciliation_discrepancy_analytics',
              'v_refund_analytics',
              'v_seller_settlement_analytics',
              'v_settlement_adjustment_analytics',
              'v_settlement_batch_analytics',
              'v_settlement_order_analytics'
          )
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
        ORDER BY relation.relname, attribute.attnum
    ) = ARRAY[
        'v_drop_analytics.created_at',
        'v_drop_analytics.deleted_at',
        'v_drop_analytics.open_at',
        'v_drop_analytics.close_at',
        'v_drop_analytics.persisted_status',
        'v_drop_analytics.current_status',
        'v_drop_analytics.category_name',
        'v_drop_analytics.inventory_state',
        'v_drop_analytics.scheduled_close_configured',
        'v_drop_analytics.user_limit_configured',
        'v_drop_analytics.product_name',
        'v_drop_analytics.drop_price',
        'v_drop_analytics.total_quantity',
        'v_drop_analytics.remaining_quantity',
        'v_drop_analytics.deducted_quantity',
        'v_drop_analytics.rollback_quantity',
        'v_drop_analytics.stock_change_count',
        'v_drop_analytics.rollback_count',
        'v_drop_analytics.drop_id',
        'v_event_pipeline_analytics.bucket_start',
        'v_event_pipeline_analytics.service_name',
        'v_event_pipeline_analytics.direction',
        'v_event_pipeline_analytics.event_type',
        'v_event_pipeline_analytics.status',
        'v_event_pipeline_analytics.oldest_event_at',
        'v_event_pipeline_analytics.event_count',
        'v_member_current_snapshot.platform_type',
        'v_member_current_snapshot.member_role',
        'v_member_current_snapshot.member_count',
        'v_member_registration_analytics.period_start',
        'v_member_registration_analytics.platform_type',
        'v_member_registration_analytics.new_member_count',
        'v_member_registration_analytics.withdrawn_member_count',
        'v_order_analytics.created_at',
        'v_order_analytics.paid_at',
        'v_order_analytics.completed_at',
        'v_order_analytics.cancelled_at',
        'v_order_analytics.refunded_at',
        'v_order_analytics.status',
        'v_order_analytics.fail_code',
        'v_order_analytics.product_name',
        'v_order_analytics.category_name',
        'v_order_analytics.quantity',
        'v_order_analytics.unit_price',
        'v_order_analytics.total_price',
        'v_order_analytics.order_number',
        'v_order_current_saga.order_number',
        'v_order_current_saga.current_step',
        'v_order_current_saga.compensating_since',
        'v_order_current_saga.saga_created_at',
        'v_order_current_saga.saga_updated_at',
        'v_order_details.order_number',
        'v_order_details.product_name',
        'v_order_details.quantity',
        'v_order_details.status',
        'v_order_details.fail_code',
        'v_order_details.payment_expires_at',
        'v_order_details.created_at',
        'v_order_details.updated_at',
        'v_order_details.paid_at',
        'v_order_details.completed_at',
        'v_order_details.cancelled_at',
        'v_order_details.refunded_at',
        'v_order_details.unit_price',
        'v_order_details.total_price',
        'v_order_process_events.order_number',
        'v_order_process_events.event_sequence',
        'v_order_process_events.occurred_at',
        'v_order_process_events.previous_status',
        'v_order_process_events.new_status',
        'v_order_process_events.reason_code',
        'v_order_saga_analytics.current_step',
        'v_order_saga_analytics.compensating_since',
        'v_order_saga_analytics.saga_created_at',
        'v_order_saga_analytics.saga_updated_at',
        'v_order_status_transitions.occurred_at',
        'v_order_status_transitions.previous_status',
        'v_order_status_transitions.new_status',
        'v_order_status_transitions.reason_code',
        'v_payment_analytics.created_at',
        'v_payment_analytics.approved_at',
        'v_payment_analytics.status',
        'v_payment_analytics.method',
        'v_payment_analytics.pg_provider',
        'v_payment_analytics.pg_recon_status',
        'v_payment_analytics.amount',
        'v_payment_analytics.refunded_amount',
        'v_payment_pending_expirations.payment_expires_at',
        'v_product_analytics.created_at',
        'v_product_analytics.deleted_at',
        'v_product_analytics.product_name',
        'v_product_analytics.category_name',
        'v_product_analytics.price',
        'v_product_analytics.price_configured',
        'v_product_analytics.description_configured',
        'v_product_analytics.image_configured',
        'v_product_analytics.wishlist_count',
        'v_product_analytics.product_id',
        'v_reconciliation_analytics.period_start',
        'v_reconciliation_analytics.executed_at',
        'v_reconciliation_analytics.status',
        'v_reconciliation_analytics.payment_count',
        'v_reconciliation_analytics.total_payment_amount',
        'v_reconciliation_analytics.refund_count',
        'v_reconciliation_analytics.total_refund_amount',
        'v_reconciliation_analytics.expected_settlement_amount',
        'v_reconciliation_analytics.discrepancy_count',
        'v_reconciliation_discrepancy_analytics.period_start',
        'v_reconciliation_discrepancy_analytics.entity_type',
        'v_reconciliation_discrepancy_analytics.discrepancy_type',
        'v_reconciliation_discrepancy_analytics.discrepancy_count',
        'v_refund_analytics.created_at',
        'v_refund_analytics.completed_at',
        'v_refund_analytics.status',
        'v_refund_analytics.pg_recon_status',
        'v_refund_analytics.amount',
        'v_seller_settlement_analytics.period_start',
        'v_seller_settlement_analytics.settlement_status',
        'v_seller_settlement_analytics.seller_count',
        'v_seller_settlement_analytics.total_order_count',
        'v_seller_settlement_analytics.total_paid_amount',
        'v_seller_settlement_analytics.total_fee_amount',
        'v_seller_settlement_analytics.total_refund_amount',
        'v_seller_settlement_analytics.total_adjustment_amount',
        'v_seller_settlement_analytics.final_settlement_amount',
        'v_settlement_adjustment_analytics.period_start',
        'v_settlement_adjustment_analytics.adjustment_type',
        'v_settlement_adjustment_analytics.status',
        'v_settlement_adjustment_analytics.adjustment_count',
        'v_settlement_adjustment_analytics.adjustment_amount',
        'v_settlement_batch_analytics.period_start',
        'v_settlement_batch_analytics.created_at',
        'v_settlement_batch_analytics.started_at',
        'v_settlement_batch_analytics.ended_at',
        'v_settlement_batch_analytics.batch_type',
        'v_settlement_batch_analytics.status',
        'v_settlement_batch_analytics.total_order_count',
        'v_settlement_batch_analytics.total_seller_count',
        'v_settlement_batch_analytics.total_settlement_amount',
        'v_settlement_order_analytics.period_start',
        'v_settlement_order_analytics.paid_at',
        'v_settlement_order_analytics.settlement_status',
        'v_settlement_order_analytics.product_name',
        'v_settlement_order_analytics.category_name',
        'v_settlement_order_analytics.order_amount',
        'v_settlement_order_analytics.paid_amount',
        'v_settlement_order_analytics.fee_amount',
        'v_settlement_order_analytics.refund_amount',
        'v_settlement_order_analytics.net_settlement_amount'
    ]
))::integer) AS preflight_exact_managed_view_columns;
SELECT 1 / (((
    ARRAY(
        SELECT routine.proname::text
        FROM pg_catalog.pg_proc routine
        JOIN pg_catalog.pg_namespace schema
          ON schema.oid = routine.pronamespace
        WHERE schema.nspname = 'ai_read'
          AND routine.prokind = 'f'
          AND routine.proname IN (
              'lookup_order_current_saga',
              'lookup_order_detail',
              'lookup_order_process_events'
          )
        ORDER BY routine.proname
    ) = ARRAY[
        'lookup_order_current_saga',
        'lookup_order_detail',
        'lookup_order_process_events'
    ]
))::integer) AS preflight_exact_managed_function_set;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_proc routine
    JOIN pg_catalog.pg_namespace schema
      ON schema.oid = routine.pronamespace
    JOIN pg_catalog.pg_roles owner
      ON owner.oid = routine.proowner
    WHERE schema.nspname = 'ai_read'
      AND routine.proname IN (
          'lookup_order_current_saga',
          'lookup_order_detail',
          'lookup_order_process_events'
      )
      AND (
          owner.rolname <> 'ai_view_owner'
          OR routine.pronargs <> 1
          OR routine.proargtypes[0] <> 'character varying'::pg_catalog.regtype
          OR NOT routine.prosecdef
          OR routine.provolatile <> 's'
          OR routine.proconfig IS DISTINCT FROM ARRAY['search_path=pg_catalog, ai_read']::text[]
          OR EXISTS (
              SELECT 1
              FROM pg_catalog.aclexplode(routine.proacl) routine_acl
              WHERE routine_acl.grantee = 0
                AND routine_acl.privilege_type = 'EXECUTE'
          )
      )
))::integer) AS preflight_managed_function_security;

SELECT count(*) FROM ai_read.v_drop_analytics WHERE false;
SELECT count(*) FROM ai_read.v_event_pipeline_analytics WHERE false;
SELECT count(*) FROM ai_read.v_member_current_snapshot WHERE false;
SELECT count(*) FROM ai_read.v_member_registration_analytics WHERE false;
SELECT count(*) FROM ai_read.v_order_analytics WHERE false;
SELECT count(*) FROM ai_read.v_order_saga_analytics WHERE false;
SELECT count(*) FROM ai_read.v_order_status_transitions WHERE false;
SELECT count(*) FROM ai_read.v_payment_analytics WHERE false;
SELECT count(*) FROM ai_read.v_payment_pending_expirations WHERE false;
SELECT count(*) FROM ai_read.v_product_analytics WHERE false;
SELECT count(*) FROM ai_read.v_reconciliation_analytics WHERE false;
SELECT count(*) FROM ai_read.v_reconciliation_discrepancy_analytics WHERE false;
SELECT count(*) FROM ai_read.v_refund_analytics WHERE false;
SELECT count(*) FROM ai_read.v_seller_settlement_analytics WHERE false;
SELECT count(*) FROM ai_read.v_settlement_adjustment_analytics WHERE false;
SELECT count(*) FROM ai_read.v_settlement_batch_analytics WHERE false;
SELECT count(*) FROM ai_read.v_settlement_order_analytics WHERE false;
SELECT order_number,
       product_name,
       quantity,
       unit_price,
       total_price,
       status,
       fail_code,
       payment_expires_at,
       created_at,
       updated_at,
       paid_at,
       completed_at,
       cancelled_at,
       refunded_at
FROM ai_read.lookup_order_detail('ORD-NOT-FOUND');
SELECT count(*) FROM ai_read.lookup_order_process_events('ORD-NOT-FOUND');
SELECT count(*) FROM ai_read.lookup_order_current_saga('ORD-NOT-FOUND');

RESET ROLE;

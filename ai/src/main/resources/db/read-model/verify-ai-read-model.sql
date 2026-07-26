\set ON_ERROR_STOP on

SELECT 1 / ((current_user = 'ai_query_app')::integer) AS correct_login_role;
SELECT 1 / ((current_setting('default_transaction_read_only') = 'on')::integer)
    AS default_read_only;
SELECT 1 / ((current_setting('transaction_read_only') = 'on')::integer)
    AS transaction_read_only;
SELECT 1 / ((current_setting('statement_timeout') = '3s')::integer)
    AS statement_timeout_is_bounded;
SELECT 1 / ((current_setting('lock_timeout') = '500ms')::integer)
    AS lock_timeout_is_bounded;

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
))::integer) AS executor_role_attributes_are_bounded;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_auth_members membership
    JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
    WHERE member_role.rolname = current_user
))::integer) AS executor_has_no_role_membership;
SELECT 1 / ((has_schema_privilege(current_user, 'ai_read', 'USAGE'))::integer)
    AS read_schema_is_visible;
SELECT 1 / ((NOT has_schema_privilege(current_user, 'ai_read', 'CREATE'))::integer)
    AS read_schema_is_not_writable;
SELECT 1 / ((NOT (
    has_schema_privilege(current_user, 'orders', 'USAGE')
    OR has_schema_privilege(current_user, 'member', 'USAGE')
    OR has_schema_privilege(current_user, 'payment', 'USAGE')
    OR has_schema_privilege(current_user, 'product', 'USAGE')
    OR has_schema_privilege(current_user, 'settlement', 'USAGE')
))::integer) AS source_schemas_are_hidden;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_class source_table
    JOIN pg_catalog.pg_namespace source_schema
      ON source_schema.oid = source_table.relnamespace
    WHERE source_schema.nspname IN ('orders', 'member', 'payment', 'product', 'settlement')
      AND source_table.relkind IN ('r', 'p', 'v', 'm', 'f')
      AND has_any_column_privilege(current_user, source_table.oid, 'SELECT')
))::integer) AS source_columns_are_hidden;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_namespace candidate_schema
    WHERE candidate_schema.nspname NOT IN ('pg_catalog', 'information_schema')
      AND candidate_schema.nspname NOT LIKE 'pg_toast%'
      AND candidate_schema.nspname NOT LIKE 'pg_temp_%'
      AND has_schema_privilege(current_user, candidate_schema.oid, 'CREATE')
))::integer) AS no_application_schema_is_writable;

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
))::integer) AS no_unapproved_relation_is_accessible;

SELECT 1 / (((ARRAY(
    SELECT (table_schema || '.' || table_name || '.' || privilege_type)::text
    FROM information_schema.role_table_grants
    WHERE grantee = current_user
      AND table_schema NOT IN ('pg_catalog', 'information_schema')
    ORDER BY table_schema, table_name, privilege_type
)) = ARRAY[
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
])::integer) AS exact_direct_relation_grants;
SELECT 1 / (((ARRAY(
    SELECT (routine_schema || '.' || routine_name || '.' || privilege_type)::text
    FROM information_schema.role_routine_grants
    WHERE grantee = current_user
      AND routine_schema = 'ai_read'
    ORDER BY routine_schema, routine_name, privilege_type
)) = ARRAY[
    'ai_read.lookup_order_current_saga.EXECUTE',
    'ai_read.lookup_order_detail.EXECUTE',
    'ai_read.lookup_order_process_events.EXECUTE'
])::integer) AS exact_direct_routine_grants;
SELECT 1 / ((NOT EXISTS (
    SELECT 1
    FROM pg_catalog.pg_attribute candidate_column
    CROSS JOIN LATERAL pg_catalog.aclexplode(candidate_column.attacl) column_acl
    WHERE column_acl.grantee = (
        SELECT role.oid
        FROM pg_catalog.pg_roles role
        WHERE role.rolname = current_user
    )
))::integer) AS no_direct_column_grants;

SELECT 1 / (((ARRAY(
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
)) = ARRAY[
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
])::integer) AS exact_managed_view_set;
SELECT 1 / (((ARRAY(
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
)) = ARRAY[
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
])::integer) AS exact_managed_view_columns;

SELECT 1 / (((ARRAY(
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
)) = ARRAY[
    'lookup_order_current_saga',
    'lookup_order_detail',
    'lookup_order_process_events'
])::integer) AS exact_managed_function_set;
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
))::integer) AS managed_lookup_functions_are_hardened;

SELECT 1 / ((NOT (
    has_table_privilege(current_user, 'ai_read.v_order_details', 'SELECT')
    OR has_table_privilege(current_user, 'ai_read.v_order_process_events', 'SELECT')
    OR has_table_privilege(current_user, 'ai_read.v_order_current_saga', 'SELECT')
))::integer) AS exact_lookup_views_are_owner_only;
SELECT 1 / ((
    has_function_privilege(
        current_user,
        'ai_read.lookup_order_detail(character varying)',
        'EXECUTE'
    )
    AND has_function_privilege(
        current_user,
        'ai_read.lookup_order_process_events(character varying)',
        'EXECUTE'
    )
    AND has_function_privilege(
        current_user,
        'ai_read.lookup_order_current_saga(character varying)',
        'EXECUTE'
    )
)::integer) AS exact_lookup_functions_are_executable;

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

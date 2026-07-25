package com.openat.chat.infrastructure.persistence;

import com.openat.chat.application.port.DataQueryCapabilityState;
import com.openat.chat.infrastructure.config.ChatQueryDataSourceProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ReadModelStartupVerifier implements ApplicationRunner, DataQueryCapabilityState {

  private static final String EXPECTED_QUERY_ROLE = "ai_query_app";
  private static final List<String> APPROVED_VIEWS =
      List.of(
          "v_drop_analytics",
          "v_event_pipeline_analytics",
          "v_member_current_snapshot",
          "v_member_registration_analytics",
          "v_order_analytics",
          "v_order_saga_analytics",
          "v_order_status_transitions",
          "v_payment_analytics",
          "v_payment_pending_expirations",
          "v_product_analytics",
          "v_reconciliation_analytics",
          "v_reconciliation_discrepancy_analytics",
          "v_refund_analytics",
          "v_seller_settlement_analytics",
          "v_settlement_adjustment_analytics",
          "v_settlement_batch_analytics",
          "v_settlement_order_analytics");
  private static final List<String> OWNER_ONLY_VIEWS =
      List.of("v_order_current_saga", "v_order_details", "v_order_process_events");
  private static final List<String> EXPECTED_VIEWS =
      List.of(
          "v_drop_analytics",
          "v_event_pipeline_analytics",
          "v_member_current_snapshot",
          "v_member_registration_analytics",
          "v_order_analytics",
          "v_order_current_saga",
          "v_order_details",
          "v_order_process_events",
          "v_order_saga_analytics",
          "v_order_status_transitions",
          "v_payment_analytics",
          "v_payment_pending_expirations",
          "v_product_analytics",
          "v_reconciliation_analytics",
          "v_reconciliation_discrepancy_analytics",
          "v_refund_analytics",
          "v_seller_settlement_analytics",
          "v_settlement_adjustment_analytics",
          "v_settlement_batch_analytics",
          "v_settlement_order_analytics");
  private static final List<ViewColumn> EXPECTED_COLUMNS =
      List.of(
          new ViewColumn("v_drop_analytics", "created_at", 1, "timestamptz"),
          new ViewColumn("v_drop_analytics", "deleted_at", 2, "timestamptz"),
          new ViewColumn("v_drop_analytics", "open_at", 3, "timestamptz"),
          new ViewColumn("v_drop_analytics", "close_at", 4, "timestamptz"),
          new ViewColumn("v_drop_analytics", "persisted_status", 5, "varchar"),
          new ViewColumn("v_drop_analytics", "current_status", 6, "text"),
          new ViewColumn("v_drop_analytics", "category_name", 7, "varchar"),
          new ViewColumn("v_drop_analytics", "inventory_state", 8, "text"),
          new ViewColumn("v_drop_analytics", "scheduled_close_configured", 9, "bool"),
          new ViewColumn("v_drop_analytics", "user_limit_configured", 10, "bool"),
          new ViewColumn("v_drop_analytics", "product_name", 11, "varchar"),
          new ViewColumn("v_drop_analytics", "drop_price", 12, "int8"),
          new ViewColumn("v_drop_analytics", "total_quantity", 13, "int4"),
          new ViewColumn("v_drop_analytics", "remaining_quantity", 14, "int8"),
          new ViewColumn("v_drop_analytics", "deducted_quantity", 15, "int8"),
          new ViewColumn("v_drop_analytics", "rollback_quantity", 16, "int8"),
          new ViewColumn("v_drop_analytics", "stock_change_count", 17, "int8"),
          new ViewColumn("v_drop_analytics", "rollback_count", 18, "int8"),
          new ViewColumn("v_drop_analytics", "drop_id", 19, "uuid"),
          new ViewColumn("v_event_pipeline_analytics", "bucket_start", 1, "timestamptz"),
          new ViewColumn("v_event_pipeline_analytics", "service_name", 2, "text"),
          new ViewColumn("v_event_pipeline_analytics", "direction", 3, "text"),
          new ViewColumn("v_event_pipeline_analytics", "event_type", 4, "text"),
          new ViewColumn("v_event_pipeline_analytics", "status", 5, "text"),
          new ViewColumn("v_event_pipeline_analytics", "oldest_event_at", 6, "timestamptz"),
          new ViewColumn("v_event_pipeline_analytics", "event_count", 7, "int8"),
          new ViewColumn("v_member_current_snapshot", "platform_type", 1, "text"),
          new ViewColumn("v_member_current_snapshot", "member_role", 2, "text"),
          new ViewColumn("v_member_current_snapshot", "member_count", 3, "int8"),
          new ViewColumn("v_member_registration_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_member_registration_analytics", "platform_type", 2, "text"),
          new ViewColumn("v_member_registration_analytics", "new_member_count", 3, "int8"),
          new ViewColumn("v_member_registration_analytics", "withdrawn_member_count", 4, "int8"),
          new ViewColumn("v_order_analytics", "created_at", 1, "timestamptz"),
          new ViewColumn("v_order_analytics", "paid_at", 2, "timestamptz"),
          new ViewColumn("v_order_analytics", "completed_at", 3, "timestamptz"),
          new ViewColumn("v_order_analytics", "cancelled_at", 4, "timestamptz"),
          new ViewColumn("v_order_analytics", "refunded_at", 5, "timestamptz"),
          new ViewColumn("v_order_analytics", "status", 6, "varchar"),
          new ViewColumn("v_order_analytics", "fail_code", 7, "varchar"),
          new ViewColumn("v_order_analytics", "product_name", 8, "varchar"),
          new ViewColumn("v_order_analytics", "category_name", 9, "varchar"),
          new ViewColumn("v_order_analytics", "quantity", 10, "int4"),
          new ViewColumn("v_order_analytics", "unit_price", 11, "int8"),
          new ViewColumn("v_order_analytics", "total_price", 12, "int8"),
          new ViewColumn("v_order_analytics", "order_number", 13, "varchar"),
          new ViewColumn("v_order_current_saga", "order_number", 1, "varchar"),
          new ViewColumn("v_order_current_saga", "current_step", 2, "varchar"),
          new ViewColumn("v_order_current_saga", "compensating_since", 3, "timestamptz"),
          new ViewColumn("v_order_current_saga", "saga_created_at", 4, "timestamptz"),
          new ViewColumn("v_order_current_saga", "saga_updated_at", 5, "timestamptz"),
          new ViewColumn("v_order_details", "order_number", 1, "varchar"),
          new ViewColumn("v_order_details", "product_name", 2, "varchar"),
          new ViewColumn("v_order_details", "quantity", 3, "int4"),
          new ViewColumn("v_order_details", "status", 4, "varchar"),
          new ViewColumn("v_order_details", "fail_code", 5, "varchar"),
          new ViewColumn("v_order_details", "payment_expires_at", 6, "timestamptz"),
          new ViewColumn("v_order_details", "created_at", 7, "timestamptz"),
          new ViewColumn("v_order_details", "updated_at", 8, "timestamptz"),
          new ViewColumn("v_order_details", "paid_at", 9, "timestamptz"),
          new ViewColumn("v_order_details", "completed_at", 10, "timestamptz"),
          new ViewColumn("v_order_details", "cancelled_at", 11, "timestamptz"),
          new ViewColumn("v_order_details", "refunded_at", 12, "timestamptz"),
          new ViewColumn("v_order_details", "unit_price", 13, "int8"),
          new ViewColumn("v_order_details", "total_price", 14, "int8"),
          new ViewColumn("v_order_process_events", "order_number", 1, "varchar"),
          new ViewColumn("v_order_process_events", "event_sequence", 2, "int8"),
          new ViewColumn("v_order_process_events", "occurred_at", 3, "timestamptz"),
          new ViewColumn("v_order_process_events", "previous_status", 4, "varchar"),
          new ViewColumn("v_order_process_events", "new_status", 5, "varchar"),
          new ViewColumn("v_order_process_events", "reason_code", 6, "varchar"),
          new ViewColumn("v_order_saga_analytics", "current_step", 1, "varchar"),
          new ViewColumn("v_order_saga_analytics", "compensating_since", 2, "timestamptz"),
          new ViewColumn("v_order_saga_analytics", "saga_created_at", 3, "timestamptz"),
          new ViewColumn("v_order_saga_analytics", "saga_updated_at", 4, "timestamptz"),
          new ViewColumn("v_order_status_transitions", "occurred_at", 1, "timestamptz"),
          new ViewColumn("v_order_status_transitions", "previous_status", 2, "varchar"),
          new ViewColumn("v_order_status_transitions", "new_status", 3, "varchar"),
          new ViewColumn("v_order_status_transitions", "reason_code", 4, "varchar"),
          new ViewColumn("v_payment_analytics", "created_at", 1, "timestamptz"),
          new ViewColumn("v_payment_analytics", "approved_at", 2, "timestamptz"),
          new ViewColumn("v_payment_analytics", "status", 3, "text"),
          new ViewColumn("v_payment_analytics", "method", 4, "text"),
          new ViewColumn("v_payment_analytics", "pg_provider", 5, "varchar"),
          new ViewColumn("v_payment_analytics", "pg_recon_status", 6, "text"),
          new ViewColumn("v_payment_analytics", "amount", 7, "int8"),
          new ViewColumn("v_payment_analytics", "refunded_amount", 8, "int8"),
          new ViewColumn("v_payment_pending_expirations", "payment_expires_at", 1, "timestamptz"),
          new ViewColumn("v_product_analytics", "created_at", 1, "timestamptz"),
          new ViewColumn("v_product_analytics", "deleted_at", 2, "timestamptz"),
          new ViewColumn("v_product_analytics", "product_name", 3, "varchar"),
          new ViewColumn("v_product_analytics", "category_name", 4, "varchar"),
          new ViewColumn("v_product_analytics", "price", 5, "int8"),
          new ViewColumn("v_product_analytics", "price_configured", 6, "bool"),
          new ViewColumn("v_product_analytics", "description_configured", 7, "bool"),
          new ViewColumn("v_product_analytics", "image_configured", 8, "bool"),
          new ViewColumn("v_product_analytics", "wishlist_count", 9, "int8"),
          new ViewColumn("v_product_analytics", "product_id", 10, "uuid"),
          new ViewColumn("v_reconciliation_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_reconciliation_analytics", "executed_at", 2, "timestamptz"),
          new ViewColumn("v_reconciliation_analytics", "status", 3, "text"),
          new ViewColumn("v_reconciliation_analytics", "payment_count", 4, "int4"),
          new ViewColumn("v_reconciliation_analytics", "total_payment_amount", 5, "int8"),
          new ViewColumn("v_reconciliation_analytics", "refund_count", 6, "int4"),
          new ViewColumn("v_reconciliation_analytics", "total_refund_amount", 7, "int8"),
          new ViewColumn("v_reconciliation_analytics", "expected_settlement_amount", 8, "int8"),
          new ViewColumn("v_reconciliation_analytics", "discrepancy_count", 9, "int4"),
          new ViewColumn(
              "v_reconciliation_discrepancy_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_reconciliation_discrepancy_analytics", "entity_type", 2, "text"),
          new ViewColumn("v_reconciliation_discrepancy_analytics", "discrepancy_type", 3, "text"),
          new ViewColumn("v_reconciliation_discrepancy_analytics", "discrepancy_count", 4, "int8"),
          new ViewColumn("v_refund_analytics", "created_at", 1, "timestamptz"),
          new ViewColumn("v_refund_analytics", "completed_at", 2, "timestamptz"),
          new ViewColumn("v_refund_analytics", "status", 3, "text"),
          new ViewColumn("v_refund_analytics", "pg_recon_status", 4, "text"),
          new ViewColumn("v_refund_analytics", "amount", 5, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_seller_settlement_analytics", "settlement_status", 2, "text"),
          new ViewColumn("v_seller_settlement_analytics", "seller_count", 3, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "total_order_count", 4, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "total_paid_amount", 5, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "total_fee_amount", 6, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "total_refund_amount", 7, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "total_adjustment_amount", 8, "int8"),
          new ViewColumn("v_seller_settlement_analytics", "final_settlement_amount", 9, "int8"),
          new ViewColumn("v_settlement_adjustment_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_settlement_adjustment_analytics", "adjustment_type", 2, "text"),
          new ViewColumn("v_settlement_adjustment_analytics", "status", 3, "text"),
          new ViewColumn("v_settlement_adjustment_analytics", "adjustment_count", 4, "int8"),
          new ViewColumn("v_settlement_adjustment_analytics", "adjustment_amount", 5, "int8"),
          new ViewColumn("v_settlement_batch_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_settlement_batch_analytics", "created_at", 2, "timestamptz"),
          new ViewColumn("v_settlement_batch_analytics", "started_at", 3, "timestamptz"),
          new ViewColumn("v_settlement_batch_analytics", "ended_at", 4, "timestamptz"),
          new ViewColumn("v_settlement_batch_analytics", "batch_type", 5, "text"),
          new ViewColumn("v_settlement_batch_analytics", "status", 6, "text"),
          new ViewColumn("v_settlement_batch_analytics", "total_order_count", 7, "int4"),
          new ViewColumn("v_settlement_batch_analytics", "total_seller_count", 8, "int4"),
          new ViewColumn("v_settlement_batch_analytics", "total_settlement_amount", 9, "int8"),
          new ViewColumn("v_settlement_order_analytics", "period_start", 1, "timestamptz"),
          new ViewColumn("v_settlement_order_analytics", "paid_at", 2, "timestamptz"),
          new ViewColumn("v_settlement_order_analytics", "settlement_status", 3, "text"),
          new ViewColumn("v_settlement_order_analytics", "product_name", 4, "varchar"),
          new ViewColumn("v_settlement_order_analytics", "category_name", 5, "varchar"),
          new ViewColumn("v_settlement_order_analytics", "order_amount", 6, "int8"),
          new ViewColumn("v_settlement_order_analytics", "paid_amount", 7, "int8"),
          new ViewColumn("v_settlement_order_analytics", "fee_amount", 8, "int8"),
          new ViewColumn("v_settlement_order_analytics", "refund_amount", 9, "int8"),
          new ViewColumn("v_settlement_order_analytics", "net_settlement_amount", 10, "int8"));
  private static final List<RelationPrivilege> EXPECTED_RELATION_PRIVILEGES =
      APPROVED_VIEWS.stream()
          .map(view -> new RelationPrivilege("ai_read", view, "SELECT"))
          .toList();
  private static final List<RoutinePrivilege> EXPECTED_ROUTINE_PRIVILEGES =
      List.of(
          new RoutinePrivilege("ai_read", "lookup_order_current_saga", "EXECUTE"),
          new RoutinePrivilege("ai_read", "lookup_order_detail", "EXECUTE"),
          new RoutinePrivilege("ai_read", "lookup_order_process_events", "EXECUTE"));
  private static final List<FunctionContract> EXPECTED_FUNCTIONS =
      List.of(
          new FunctionContract(
              "lookup_order_current_saga",
              1,
              "character varying",
              "ai_view_owner",
              true,
              "s",
              true,
              true),
          new FunctionContract(
              "lookup_order_detail",
              1,
              "character varying",
              "ai_view_owner",
              true,
              "s",
              true,
              true),
          new FunctionContract(
              "lookup_order_process_events",
              1,
              "character varying",
              "ai_view_owner",
              true,
              "s",
              true,
              true));

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final ChatQueryDataSourceProperties properties;
  private final AtomicReference<Availability> availability =
      new AtomicReference<>(Availability.NOT_CHECKED);

  public ReadModelStartupVerifier(
      @Qualifier("chatQueryJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
      ChatQueryDataSourceProperties properties) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    verifyNow();
  }

  public synchronized Availability verifyNow() {
    if (!properties.isConfigured()) {
      availability.set(Availability.UNAVAILABLE);
      log.info("관리자 AI 데이터 조회 capability가 비활성 상태야. reason=NOT_CONFIGURED");
      return availability.get();
    }

    VerificationResult result;
    try {
      result =
          jdbcTemplate
              .getJdbcTemplate()
              .execute((ConnectionCallback<VerificationResult>) this::verifyConnection);
      availability.set(
          result.check() == VerificationCheck.PASSED
              ? Availability.AVAILABLE
              : Availability.UNAVAILABLE);
    } catch (RuntimeException exception) {
      result =
          VerificationResult.failed(
              VerificationCheck.CONNECTION,
              List.of("exception=" + exception.getClass().getSimpleName()));
      availability.set(Availability.UNAVAILABLE);
    }

    if (availability.get() == Availability.AVAILABLE) {
      log.info("관리자 AI 데이터 조회 capability 검증을 통과했어.");
    } else {
      log.warn(
          "관리자 AI 데이터 조회 capability를 비활성화했어. reason={}, violations={}",
          result.check(),
          result.violations());
    }
    return availability.get();
  }

  @Override
  public Availability availability() {
    return availability.get();
  }

  private VerificationResult verifyConnection(Connection connection) throws SQLException {
    if (!hasExpectedSession(connection)) {
      return VerificationResult.failed(VerificationCheck.SESSION);
    }
    List<String> roleViolations = roleBoundaryViolations(connection);
    if (!roleViolations.isEmpty()) {
      return VerificationResult.failed(VerificationCheck.ROLE_BOUNDARY, roleViolations);
    }
    if (!hasExactContract(connection)) {
      return VerificationResult.failed(VerificationCheck.READ_MODEL_CONTRACT);
    }
    if (!hasApprovedPrivileges(connection)) {
      return VerificationResult.failed(VerificationCheck.READ_MODEL_GRANTS);
    }
    if (!canReadApprovedCapabilities(connection)) {
      return VerificationResult.failed(VerificationCheck.READ_MODEL_READ);
    }
    return VerificationResult.passed();
  }

  private boolean hasExpectedSession(Connection connection) throws SQLException {
    String sql =
        """
        SELECT current_user,
               current_setting('default_transaction_read_only'),
               current_setting('transaction_read_only'),
               current_setting('statement_timeout'),
               current_setting('lock_timeout')
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next()
          && EXPECTED_QUERY_ROLE.equals(resultSet.getString(1))
          && EXPECTED_QUERY_ROLE.equals(properties.getUsername())
          && "on".equals(resultSet.getString(2))
          && "on".equals(resultSet.getString(3))
          && "3s".equals(resultSet.getString(4))
          && "500ms".equals(resultSet.getString(5));
    }
  }

  private List<String> roleBoundaryViolations(Connection connection) throws SQLException {
    String sql =
        """
        SELECT role.rolsuper AS is_superuser,
               role.rolinherit AS inherits_privileges,
               role.rolcreaterole AS can_create_role,
               role.rolcreatedb AS can_create_database,
               role.rolreplication AS can_replicate,
               role.rolbypassrls AS bypasses_rls,
               role.rolconnlimit AS connection_limit,
               NOT EXISTS (
                   SELECT 1
                   FROM pg_catalog.pg_auth_members membership
                   JOIN pg_catalog.pg_roles member_role
                     ON member_role.oid = membership.member
                   WHERE member_role.rolname = current_user
               ) AS has_no_role_memberships,
               has_schema_privilege(current_user, 'ai_read', 'USAGE') AS can_use_ai_read,
               has_schema_privilege(current_user, 'ai_read', 'CREATE') AS can_create_in_ai_read,
               (
                   has_schema_privilege(current_user, 'orders', 'USAGE')
                   OR has_schema_privilege(current_user, 'member', 'USAGE')
                   OR has_schema_privilege(current_user, 'payment', 'USAGE')
                   OR has_schema_privilege(current_user, 'product', 'USAGE')
                   OR has_schema_privilege(current_user, 'settlement', 'USAGE')
               ) AS can_use_source_schemas,
               EXISTS (
                   SELECT 1
                   FROM pg_catalog.pg_class source_table
                   JOIN pg_catalog.pg_namespace source_schema
                     ON source_schema.oid = source_table.relnamespace
                   WHERE source_schema.nspname
                       IN ('orders', 'member', 'payment', 'product', 'settlement')
                     AND source_table.relkind IN ('r', 'p', 'v', 'm', 'f')
                     AND has_any_column_privilege(
                         current_user, source_table.oid, 'SELECT'
                     )
               ) AS can_select_source_columns,
               NOT EXISTS (
                   SELECT 1
                   FROM pg_catalog.pg_namespace candidate_schema
                   WHERE candidate_schema.nspname NOT IN ('pg_catalog', 'information_schema')
                     AND candidate_schema.nspname NOT LIKE 'pg_toast%'
                     AND candidate_schema.nspname NOT LIKE 'pg_temp_%'
                     AND has_schema_privilege(current_user, candidate_schema.oid, 'CREATE')
               ) AS has_no_unapproved_schema_create,
               NOT EXISTS (
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
                         has_table_privilege(
                             current_user, candidate_relation.oid, 'SELECT'
                         )
                         OR has_table_privilege(
                             current_user, candidate_relation.oid, 'INSERT'
                         )
                         OR has_table_privilege(
                             current_user, candidate_relation.oid, 'UPDATE'
                         )
                         OR has_table_privilege(
                             current_user, candidate_relation.oid, 'DELETE'
                         )
                         OR has_table_privilege(
                             current_user, candidate_relation.oid, 'TRUNCATE'
                         )
                         OR has_table_privilege(
                             current_user, candidate_relation.oid, 'REFERENCES'
                         )
                         OR has_table_privilege(
                             current_user, candidate_relation.oid, 'TRIGGER'
                         )
                         OR has_any_column_privilege(
                             current_user, candidate_relation.oid, 'SELECT'
                         )
                         OR has_any_column_privilege(
                             current_user, candidate_relation.oid, 'INSERT'
                         )
                         OR has_any_column_privilege(
                             current_user, candidate_relation.oid, 'UPDATE'
                         )
                         OR has_any_column_privilege(
                             current_user, candidate_relation.oid, 'REFERENCES'
                         )
                     )
               ) AS has_no_unapproved_relation_privileges,
               NOT EXISTS (
                   SELECT 1
                   FROM pg_catalog.pg_attribute candidate_column
                   CROSS JOIN LATERAL pg_catalog.aclexplode(candidate_column.attacl) column_acl
                   WHERE column_acl.grantee = (
                       SELECT query_role.oid
                       FROM pg_catalog.pg_roles query_role
                       WHERE query_role.rolname = current_user
                   )
               ) AS has_no_direct_column_privileges
        FROM pg_catalog.pg_roles role
        WHERE role.rolname = current_user
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        return List.of("queryRoleRow=missing");
      }
      return RoleBoundarySnapshot.from(resultSet).violations();
    }
  }

  private boolean hasExactContract(Connection connection) throws SQLException {
    String viewSql =
        """
        SELECT view.viewname
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
        """;
    List<String> views = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(viewSql);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        views.add(resultSet.getString(1));
      }
    }

    String columnSql =
        """
        SELECT relation.relname,
               attribute.attname,
               attribute.attnum,
               type.typname
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace schema
          ON schema.oid = relation.relnamespace
        JOIN pg_catalog.pg_attribute attribute
          ON attribute.attrelid = relation.oid
        JOIN pg_catalog.pg_type type
          ON type.oid = attribute.atttypid
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
        """;
    List<ViewColumn> columns = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(columnSql);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        columns.add(
            new ViewColumn(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getInt(3),
                resultSet.getString(4)));
      }
    }

    String functionSql =
        """
        SELECT routine.proname,
               routine.pronargs,
               routine.proargtypes[0]::pg_catalog.regtype::text,
               owner.rolname,
               routine.prosecdef,
               routine.provolatile::text,
               routine.proconfig = ARRAY['search_path=pg_catalog, ai_read']::text[],
               NOT EXISTS (
                   SELECT 1
                   FROM pg_catalog.aclexplode(routine.proacl) routine_acl
                   WHERE routine_acl.grantee = 0
                     AND routine_acl.privilege_type = 'EXECUTE'
               )
        FROM pg_catalog.pg_proc routine
        JOIN pg_catalog.pg_namespace schema
          ON schema.oid = routine.pronamespace
        JOIN pg_catalog.pg_roles owner
          ON owner.oid = routine.proowner
        WHERE schema.nspname = 'ai_read'
          AND routine.prokind = 'f'
          AND routine.proname IN (
              'lookup_order_current_saga',
              'lookup_order_detail',
              'lookup_order_process_events'
          )
        ORDER BY routine.proname
        """;
    List<FunctionContract> functions = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(functionSql);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        functions.add(
            new FunctionContract(
                resultSet.getString(1),
                resultSet.getInt(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getBoolean(5),
                resultSet.getString(6),
                resultSet.getBoolean(7),
                resultSet.getBoolean(8)));
      }
    }

    return EXPECTED_VIEWS.equals(views)
        && EXPECTED_COLUMNS.equals(columns)
        && EXPECTED_FUNCTIONS.equals(functions);
  }

  private boolean hasApprovedPrivileges(Connection connection) throws SQLException {
    for (String view : APPROVED_VIEWS) {
      if (!hasReadOnlyViewPrivilege(connection, view, true)) {
        return false;
      }
    }
    for (String view : OWNER_ONLY_VIEWS) {
      if (!hasReadOnlyViewPrivilege(connection, view, false)) {
        return false;
      }
    }
    if (!hasExactDirectRelationPrivileges(connection)) {
      return false;
    }
    if (!hasExactDirectRoutinePrivileges(connection)) {
      return false;
    }
    return hasExpectedRoutineExecutionPrivileges(connection);
  }

  private boolean hasReadOnlyViewPrivilege(
      Connection connection, String view, boolean selectExpected) throws SQLException {
    String relation = "ai_read." + view;
    String sql =
        """
        SELECT has_table_privilege(current_user, ?, 'SELECT') = ?,
               NOT (
                   has_table_privilege(current_user, ?, 'INSERT')
                   OR has_table_privilege(current_user, ?, 'UPDATE')
                   OR has_table_privilege(current_user, ?, 'DELETE')
                   OR has_table_privilege(current_user, ?, 'TRUNCATE')
                   OR has_table_privilege(current_user, ?, 'REFERENCES')
                   OR has_table_privilege(current_user, ?, 'TRIGGER')
                   OR has_any_column_privilege(current_user, ?, 'INSERT')
                   OR has_any_column_privilege(current_user, ?, 'UPDATE')
                   OR has_any_column_privilege(current_user, ?, 'REFERENCES')
               )
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, relation);
      statement.setBoolean(2, selectExpected);
      for (int parameter = 3; parameter <= 11; parameter++) {
        statement.setString(parameter, relation);
      }
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getBoolean(1) && resultSet.getBoolean(2);
      }
    }
  }

  private boolean hasExactDirectRelationPrivileges(Connection connection) throws SQLException {
    String sql =
        """
        SELECT table_schema, table_name, privilege_type
        FROM information_schema.role_table_grants
        WHERE grantee = current_user
          AND table_schema NOT IN ('pg_catalog', 'information_schema')
        ORDER BY table_schema, table_name, privilege_type
        """;
    List<RelationPrivilege> privileges = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        privileges.add(
            new RelationPrivilege(
                resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
      }
    }
    return EXPECTED_RELATION_PRIVILEGES.equals(privileges);
  }

  private boolean hasExactDirectRoutinePrivileges(Connection connection) throws SQLException {
    String sql =
        """
        SELECT routine_schema, routine_name, privilege_type
        FROM information_schema.role_routine_grants
        WHERE grantee = current_user
          AND routine_schema = 'ai_read'
        ORDER BY routine_schema, routine_name, privilege_type
        """;
    List<RoutinePrivilege> privileges = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      while (resultSet.next()) {
        privileges.add(
            new RoutinePrivilege(
                resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
      }
    }
    return EXPECTED_ROUTINE_PRIVILEGES.equals(privileges);
  }

  private boolean hasExpectedRoutineExecutionPrivileges(Connection connection) throws SQLException {
    String sql =
        """
        SELECT has_function_privilege(
                   current_user,
                   'ai_read.lookup_order_detail(character varying)',
                   'EXECUTE'
               ),
               has_function_privilege(
                   current_user,
                   'ai_read.lookup_order_process_events(character varying)',
                   'EXECUTE'
               ),
               has_function_privilege(
                   current_user,
                   'ai_read.lookup_order_current_saga(character varying)',
                   'EXECUTE'
               )
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet resultSet = statement.executeQuery()) {
      return resultSet.next()
          && resultSet.getBoolean(1)
          && resultSet.getBoolean(2)
          && resultSet.getBoolean(3);
    }
  }

  private boolean canReadApprovedCapabilities(Connection connection) throws SQLException {
    for (String view : APPROVED_VIEWS) {
      if (!canExecute(connection, "SELECT count(*) FROM ai_read." + view + " WHERE false")) {
        return false;
      }
    }
    return canExecute(
            connection,
            """
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
            FROM ai_read.lookup_order_detail('ORD-NOT-FOUND')
            """)
        && canExecute(
            connection,
            """
            SELECT event_sequence,
                   occurred_at,
                   previous_status,
                   new_status,
                   reason_code
            FROM ai_read.lookup_order_process_events('ORD-NOT-FOUND')
            """)
        && canExecute(
            connection,
            """
            SELECT current_step,
                   compensating_since,
                   saga_created_at,
                   saga_updated_at
            FROM ai_read.lookup_order_current_saga('ORD-NOT-FOUND')
            """);
  }

  private boolean canExecute(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet ignored = statement.executeQuery()) {
      return true;
    }
  }

  private record ViewColumn(String view, String column, int position, String dataType) {}

  private record RelationPrivilege(String schema, String relation, String privilege) {}

  private record RoutinePrivilege(String schema, String routine, String privilege) {}

  private record FunctionContract(
      String function,
      int argumentCount,
      String inputType,
      String owner,
      boolean securityDefiner,
      String volatility,
      boolean fixedSearchPath,
      boolean publicExecuteRevoked) {}

  private record RoleBoundarySnapshot(
      boolean superuser,
      boolean inheritsPrivileges,
      boolean canCreateRole,
      boolean canCreateDatabase,
      boolean canReplicate,
      boolean bypassesRls,
      int connectionLimit,
      boolean hasNoRoleMemberships,
      boolean canUseAiRead,
      boolean canCreateInAiRead,
      boolean canUseSourceSchemas,
      boolean canSelectSourceColumns,
      boolean hasNoUnapprovedSchemaCreate,
      boolean hasNoUnapprovedRelationPrivileges,
      boolean hasNoDirectColumnPrivileges) {

    private static RoleBoundarySnapshot from(ResultSet resultSet) throws SQLException {
      return new RoleBoundarySnapshot(
          resultSet.getBoolean("is_superuser"),
          resultSet.getBoolean("inherits_privileges"),
          resultSet.getBoolean("can_create_role"),
          resultSet.getBoolean("can_create_database"),
          resultSet.getBoolean("can_replicate"),
          resultSet.getBoolean("bypasses_rls"),
          resultSet.getInt("connection_limit"),
          resultSet.getBoolean("has_no_role_memberships"),
          resultSet.getBoolean("can_use_ai_read"),
          resultSet.getBoolean("can_create_in_ai_read"),
          resultSet.getBoolean("can_use_source_schemas"),
          resultSet.getBoolean("can_select_source_columns"),
          resultSet.getBoolean("has_no_unapproved_schema_create"),
          resultSet.getBoolean("has_no_unapproved_relation_privileges"),
          resultSet.getBoolean("has_no_direct_column_privileges"));
    }

    private List<String> violations() {
      List<String> violations = new ArrayList<>();
      addViolation(violations, superuser, "superuser=enabled");
      addViolation(violations, inheritsPrivileges, "inherit=enabled");
      addViolation(violations, canCreateRole, "createRole=allowed");
      addViolation(violations, canCreateDatabase, "createDatabase=allowed");
      addViolation(violations, canReplicate, "replication=allowed");
      addViolation(violations, bypassesRls, "bypassRls=allowed");
      addViolation(
          violations, connectionLimit != 4, "connectionLimit=" + connectionLimit + ", expected=4");
      addViolation(violations, !hasNoRoleMemberships, "roleMembership=present");
      addViolation(violations, !canUseAiRead, "aiReadUsage=missing");
      addViolation(violations, canCreateInAiRead, "aiReadCreate=allowed");
      addViolation(violations, canUseSourceSchemas, "sourceSchemaUsage=allowed");
      addViolation(violations, canSelectSourceColumns, "sourceColumnSelect=allowed");
      addViolation(violations, !hasNoUnapprovedSchemaCreate, "unapprovedSchemaCreate=allowed");
      addViolation(
          violations, !hasNoUnapprovedRelationPrivileges, "unapprovedRelationPrivilege=present");
      addViolation(violations, !hasNoDirectColumnPrivileges, "directColumnPrivilege=present");
      return List.copyOf(violations);
    }

    private void addViolation(List<String> violations, boolean violated, String description) {
      if (violated) {
        violations.add(description);
      }
    }
  }

  private record VerificationResult(VerificationCheck check, List<String> violations) {

    private VerificationResult {
      violations = List.copyOf(violations);
    }

    private static VerificationResult passed() {
      return new VerificationResult(VerificationCheck.PASSED, List.of());
    }

    private static VerificationResult failed(VerificationCheck check) {
      return failed(check, List.of());
    }

    private static VerificationResult failed(VerificationCheck check, List<String> violations) {
      return new VerificationResult(check, violations);
    }
  }

  private enum VerificationCheck {
    PASSED,
    CONNECTION,
    SESSION,
    ROLE_BOUNDARY,
    READ_MODEL_CONTRACT,
    READ_MODEL_GRANTS,
    READ_MODEL_READ
  }
}

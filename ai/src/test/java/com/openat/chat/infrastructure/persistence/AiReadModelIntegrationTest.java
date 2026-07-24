package com.openat.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult;
import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.application.port.DataQueryCapabilityState.Availability;
import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.PlanningDateRange;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dimension;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Measure;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Query;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.TimeField;
import com.openat.chat.infrastructure.config.ChatDataSourceConfig;
import com.openat.chat.infrastructure.config.ChatQueryDataSourceProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("관리자 AI read-model PostgreSQL 권한 경계")
class AiReadModelIntegrationTest {

  private static final String QUERY_ROLE = "ai_query_app";
  private static final String TEST_QUERY_PASSWORD = "read-model-test-password";

  @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @BeforeAll
  static void setUpReadModel() throws Exception {
    createSourceTables();
    createSharedReadModelView();
    applyReadModel(renderDdl(TEST_QUERY_PASSWORD));
    seedOrders();
    seedMemberAndProductFacts();
    seedExtendedAnalyticsFacts();
  }

  @Test
  @DisplayName("실행 역할은 승인된 집계 뷰만 직접 조회하고 민감 컬럼 계약을 갖지 않는다")
  void queryRole_selectViews_returnsOnlyApprovedFacts() throws SQLException {
    // given
    try (Connection connection = queryConnection()) {
      // when
      List<List<Object>> orderRows =
          rows(
              connection,
              "SELECT created_at, status FROM ai_read.v_order_analytics ORDER BY status");
      List<List<Object>> expirationRows =
          rows(connection, "SELECT payment_expires_at FROM ai_read.v_payment_pending_expirations");
      List<List<Object>> memberRows =
          rows(
              connection,
              """
              SELECT platform_type, member_role, member_count
              FROM ai_read.v_member_current_snapshot
              ORDER BY platform_type, member_role
              """);
      List<List<Object>> transitionRows =
          rows(
              connection,
              """
              SELECT previous_status, new_status, reason_code
              FROM ai_read.v_order_status_transitions
              ORDER BY occurred_at
              """);
      List<org.assertj.core.groups.Tuple> columns = viewColumns(connection);
      List<String> columnNames = viewColumnNames(connection);

      // then
      assertThat(orderRows)
          .extracting(row -> row.get(1))
          .containsExactlyInAnyOrder(
              "CANCELLED",
              "COMPLETED",
              "FAILED",
              "FAILED",
              "PAYMENT_PENDING",
              "PAYMENT_PENDING",
              "REFUNDED",
              "REFUND_PENDING");
      assertThat(expirationRows).hasSize(1);
      assertThat(memberRows)
          .containsExactly(
              List.of("GOOGLE", "UNASSIGNED", 1L),
              List.of("KAKAO", "ROLE_SELLER", 1L),
              List.of("LOCAL", "ROLE_USER", 1L));
      assertThat(transitionRows)
          .containsExactly(
              java.util.Arrays.asList(null, "FAILED", "ORDER_CREATED"),
              java.util.Arrays.asList(null, "PAYMENT_PENDING", "ORDER_CREATED"),
              List.of("PAYMENT_PENDING", "COMPLETED", "PAYMENT_APPROVED"));
      assertThat(columns)
          .contains(
              tuple("v_order_analytics", "created_at", 1),
              tuple("v_order_analytics", "status", 6),
              tuple("v_order_analytics", "total_price", 12),
              tuple("v_order_analytics", "order_number", 13),
              tuple("v_product_analytics", "product_id", 10),
              tuple("v_drop_analytics", "drop_id", 19),
              tuple("v_member_current_snapshot", "platform_type", 1),
              tuple("v_member_current_snapshot", "member_role", 2),
              tuple("v_member_current_snapshot", "member_count", 3),
              tuple("v_payment_pending_expirations", "payment_expires_at", 1));
      assertThat(columnNames)
          .doesNotContain(
              "id",
              "member_id",
              "seller_id",
              "buyer_id",
              "payment_id",
              "aggregate_id",
              "reference_id",
              "email",
              "nickname",
              "fail_message",
              "fail_reason",
              "reason_message",
              "source_event_key",
              "saga_id",
              "quantity_delta",
              "payload",
              "error_message",
              "pg_payment_key",
              "pg_payment_key_hash",
              "pg_tx_id",
              "pg_refund_key",
              "idempotency_key",
              "request_hash",
              "detail");
    }
  }

  @Test
  @DisplayName("상품과 드롭은 공개 식별자·가격·재고를 제공하고 개인·판매자 식별자는 제외한다")
  void queryRole_productAndDropViews_returnSanitizedFacts() throws SQLException {
    // given
    try (Connection connection = queryConnection()) {
      // when
      List<List<Object>> productRows =
          rows(
              connection,
              """
              SELECT product_name,
                     category_name,
                     price,
                     wishlist_count,
                     price_configured,
                     description_configured,
                     image_configured
              FROM ai_read.v_product_analytics
              ORDER BY category_name, product_name
              """);
      List<List<Object>> dropRows =
          rows(
              connection,
              """
              SELECT persisted_status,
                     current_status,
                     category_name,
                     inventory_state,
                     scheduled_close_configured,
                     user_limit_configured,
                     product_name,
                     drop_price,
                     total_quantity,
                     remaining_quantity,
                     deducted_quantity,
                     rollback_quantity,
                     stock_change_count,
                     rollback_count
              FROM ai_read.v_drop_analytics
              ORDER BY category_name, product_name
              """);

      // then
      assertThat(productRows)
          .containsExactly(
              List.of("Private Product Name 1", "Fashion", 10000L, 2L, true, true, true),
              List.of("Deleted Product", "UNCLASSIFIED", 30000L, 0L, true, true, true),
              java.util.Arrays.asList(
                  "Private Product Name 2", "UNCLASSIFIED", null, 1L, false, false, false));
      assertThat(dropRows)
          .containsExactly(
              List.of(
                  "REGISTERED",
                  "SOLD_OUT",
                  "Fashion",
                  "SOLD_OUT",
                  false,
                  true,
                  "Private Product Name 1",
                  9000L,
                  10,
                  0L,
                  10L,
                  0L,
                  1L,
                  0L),
              List.of(
                  "CLOSE",
                  "CLOSE",
                  "UNCLASSIFIED",
                  "AVAILABLE",
                  true,
                  false,
                  "Private Product Name 2",
                  19000L,
                  5,
                  4L,
                  2L,
                  1L,
                  2L,
                  1L));
    }
  }

  @Test
  @DisplayName("결제·정산·회원·이벤트 뷰는 KST 기준 비식별 운영 fact와 사전 집계만 제공한다")
  void queryRole_extendedAnalyticsViews_returnSanitizedKstFacts() throws SQLException {
    try (Connection connection = queryConnection()) {
      List<List<Object>> payments =
          rows(
              connection,
              """
              SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'),
                     status,
                     method,
                     pg_provider,
                     pg_recon_status,
                     amount,
                     refunded_amount
              FROM ai_read.v_payment_analytics
              ORDER BY amount
              """);
      List<List<Object>> refunds =
          rows(
              connection,
              """
              SELECT status, pg_recon_status, amount
              FROM ai_read.v_refund_analytics
              """);
      List<List<Object>> settlementOrders =
          rows(
              connection,
              """
              SELECT to_char(period_start AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'),
                     settlement_status,
                     product_name,
                     category_name,
                     order_amount,
                     paid_amount,
                     fee_amount,
                     refund_amount,
                     net_settlement_amount
              FROM ai_read.v_settlement_order_analytics
              ORDER BY order_amount
              """);
      List<List<Object>> sellerSettlements =
          rows(
              connection,
              """
              SELECT settlement_status,
                     seller_count,
                     total_order_count,
                     total_paid_amount,
                     total_fee_amount,
                     total_refund_amount,
                     total_adjustment_amount,
                     final_settlement_amount
              FROM ai_read.v_seller_settlement_analytics
              """);
      List<List<Object>> adjustments =
          rows(
              connection,
              """
              SELECT adjustment_type, status, adjustment_count, adjustment_amount
              FROM ai_read.v_settlement_adjustment_analytics
              """);
      List<List<Object>> settlementBatches =
          rows(
              connection,
              """
              SELECT batch_type,
                     status,
                     total_order_count,
                     total_seller_count,
                     total_settlement_amount
              FROM ai_read.v_settlement_batch_analytics
              """);
      List<List<Object>> reconciliation =
          rows(
              connection,
              """
              SELECT status,
                     payment_count,
                     total_payment_amount,
                     refund_count,
                     total_refund_amount,
                     expected_settlement_amount,
                     discrepancy_count
              FROM ai_read.v_reconciliation_analytics
              """);
      List<List<Object>> discrepancies =
          rows(
              connection,
              """
              SELECT entity_type, discrepancy_type, discrepancy_count
              FROM ai_read.v_reconciliation_discrepancy_analytics
              ORDER BY entity_type
              """);
      List<List<Object>> memberLifecycleTotals =
          rows(
              connection,
              """
              SELECT sum(new_member_count)::bigint, sum(withdrawn_member_count)::bigint
              FROM ai_read.v_member_registration_analytics
              """);
      List<List<Object>> memberLifecycleBucket =
          rows(
              connection,
              """
              SELECT to_char(period_start AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI')
              FROM ai_read.v_member_registration_analytics
              WHERE platform_type = 'LOCAL'
              """);
      List<List<Object>> sagaFacts =
          rows(
              connection,
              """
              SELECT current_step
              FROM ai_read.v_order_saga_analytics
              """);
      List<List<Object>> eventFacts =
          rows(
              connection,
              """
              SELECT service_name,
                     direction,
                     event_type,
                     status,
                     to_char(oldest_event_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI'),
                     event_count
              FROM ai_read.v_event_pipeline_analytics
              ORDER BY service_name, direction
              """);

      assertThat(payments)
          .containsExactly(
              List.of("2026-07-21 01:15", "APPROVED", "PG", "TOSS", "MATCHED", 10000L, 3000L),
              java.util.Arrays.asList(
                  "2026-07-21 02:15", "FAILED", "WALLET", null, "NOT_CHECKED", 20000L, 0L));
      assertThat(refunds).containsExactly(List.of("COMPLETE", "MATCHED", 3000L));
      assertThat(settlementOrders)
          .containsExactly(
              List.of(
                  "2026-06-30 15:00",
                  "READY",
                  "Private Product Name 1",
                  "Fashion",
                  10000L,
                  10000L,
                  1000L,
                  3000L,
                  6000L),
              List.of(
                  "2026-06-30 15:00",
                  "COMPLETED",
                  "Private Product Name 2",
                  "UNCLASSIFIED",
                  20000L,
                  20000L,
                  2000L,
                  0L,
                  18000L));
      assertThat(sellerSettlements)
          .containsExactly(List.of("COMPLETED", 2L, 3L, 30000L, 3000L, 3000L, -500L, 23500L));
      assertThat(adjustments).containsExactly(List.of("POST_REFUND", "APPLIED", 2L, -3500L));
      assertThat(settlementBatches)
          .containsExactly(List.of("SETTLEMENT_RUN", "COMPLETED", 3, 2, 23500L));
      assertThat(reconciliation)
          .containsExactly(List.of("DISCREPANCY_FOUND", 2, 30000L, 1, 3000L, 23500L, 3));
      assertThat(discrepancies)
          .containsExactly(
              List.of("ORDER", "AMOUNT_MISMATCH", 2L),
              List.of("REFUND", "MISSING_IN_SETTLEMENT", 1L));
      assertThat(memberLifecycleTotals).containsExactly(List.of(4L, 1L));
      assertThat(memberLifecycleBucket).containsExactly(List.of("2026-07-18 15:00"));
      assertThat(sagaFacts).containsExactly(List.of("PAYMENT_COMPLETED"));
      assertThat(eventFacts)
          .containsExactly(
              List.of("member", "OUTBOX", "member.created", "PENDING", "2026-07-21 01:40", 1L),
              List.of("orders", "INBOX", "PaymentCompleted", "PROCESSED", "2026-07-21 01:10", 1L),
              List.of("orders", "OUTBOX", "order.completed", "PUBLISHED", "2026-07-21 01:20", 1L),
              List.of(
                  "payment", "OUTBOX", "payment.completed", "PUBLISHED", "2026-07-21 01:30", 1L));
    }
  }

  @Test
  @DisplayName("개별 주문은 외부 주문번호 함수로만 상세·처리 이벤트·현재 사가를 조회한다")
  void queryRole_exactOrderLookup_isScopedAndOwnerViewsStayHidden() throws SQLException {
    // given
    try (Connection connection = queryConnection()) {
      // when
      List<List<Object>> detailRows =
          rows(
              connection,
              """
              SELECT order_number, product_name, quantity, unit_price, total_price, status, fail_code
              FROM ai_read.lookup_order_detail('ORD-FIXTURE-006')
              """);
      List<List<Object>> eventRows =
          rows(
              connection,
              """
              SELECT event_sequence, previous_status, new_status, reason_code
              FROM ai_read.lookup_order_process_events('ORD-FIXTURE-006')
              ORDER BY event_sequence
              """);
      List<List<Object>> sagaRows =
          rows(
              connection,
              """
              SELECT current_step, compensating_since
              FROM ai_read.lookup_order_current_saga('ORD-FIXTURE-006')
              """);
      List<List<Object>> missingRows =
          rows(connection, "SELECT order_number FROM ai_read.lookup_order_detail('ORD-NOT-FOUND')");
      List<List<Object>> wildcardRows =
          rows(connection, "SELECT order_number FROM ai_read.lookup_order_detail('ORD-FIXTURE-%')");

      // then
      assertThat(detailRows)
          .containsExactly(
              java.util.Arrays.asList(
                  "ORD-FIXTURE-006", "Exact Product", 2, 10_000L, 20_000L, "COMPLETED", null));
      assertThat(eventRows)
          .containsExactly(
              java.util.Arrays.asList(1L, null, "PAYMENT_PENDING", "ORDER_CREATED"),
              List.of(2L, "PAYMENT_PENDING", "COMPLETED", "PAYMENT_APPROVED"));
      assertThat(sagaRows).containsExactly(java.util.Arrays.asList("PAYMENT_COMPLETED", null));
      assertThat(missingRows).isEmpty();
      assertThat(wildcardRows).isEmpty();
    }

    // when & then
    assertThatThrownBy(() -> executeAsQueryRole("SELECT order_number FROM ai_read.v_order_details"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isEqualTo("42501");
    assertThatThrownBy(
            () -> executeAsQueryRole("SELECT order_number FROM ai_read.v_order_process_events"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isEqualTo("42501");
    assertThatThrownBy(
            () -> executeAsQueryRole("SELECT order_number FROM ai_read.v_order_current_saga"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isEqualTo("42501");
  }

  @Test
  @DisplayName("뷰 소유자는 계약된 원본 컬럼만 읽고 실행 역할과 멤버 관계를 맺지 않는다")
  void roles_created_keepOwnerAndExecutorSeparated() throws SQLException {
    // given
    try (Connection connection = adminConnection()) {
      // when
      List<List<Object>> sourceColumnGrants =
          rows(
              connection,
              """
              SELECT column_name, privilege_type
              FROM information_schema.column_privileges
              WHERE grantee = 'ai_view_owner'
                AND table_schema = 'orders'
                AND table_name = 'orders'
              ORDER BY column_name
              """);
      List<List<Object>> viewOwners =
          rows(
              connection,
              """
              SELECT viewname, viewowner
              FROM pg_catalog.pg_views
              WHERE schemaname = 'ai_read'
              ORDER BY viewname
              """);
      String membershipCount;
      String connectionLimit;
      String sensitiveColumnGrant;
      try (Statement statement = connection.createStatement()) {
        membershipCount =
            scalar(
                statement,
                """
                SELECT count(*)
                FROM pg_catalog.pg_auth_members membership
                JOIN pg_catalog.pg_roles granted_role ON granted_role.oid = membership.roleid
                JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
                WHERE member_role.rolname = 'ai_query_app'
                """);
        connectionLimit =
            scalar(
                statement,
                "SELECT rolconnlimit FROM pg_catalog.pg_roles WHERE rolname = 'ai_query_app'");
        sensitiveColumnGrant =
            scalar(
                statement,
                """
                SELECT (
                    has_column_privilege(
                        'ai_view_owner', 'orders.orders', 'member_id', 'SELECT'
                    )
                    OR has_column_privilege(
                        'ai_view_owner', 'orders.orders', 'fail_message', 'SELECT'
                    )
                    OR has_column_privilege(
                        'ai_view_owner', 'payment.payments', 'pg_payment_key', 'SELECT'
                    )
                    OR has_column_privilege(
                        'ai_view_owner', 'payment.payments', 'idempotency_key', 'SELECT'
                    )
                    OR has_column_privilege(
                        'ai_view_owner', 'payment.refunds', 'reason', 'SELECT'
                    )
                    OR has_column_privilege(
                        'ai_view_owner', 'settlement.settlement_orders', 'seller_id', 'SELECT'
                    )
                    OR has_column_privilege(
                        'ai_view_owner', 'settlement.daily_reconciliation_discrepancies',
                        'reference_id', 'SELECT'
                    )
                )
                """);
      }

      // then
      assertThat(sourceColumnGrants)
          .containsExactly(
              List.of("cancelled_at", "SELECT"),
              List.of("completed_at", "SELECT"),
              List.of("created_at", "SELECT"),
              List.of("deleted_at", "SELECT"),
              List.of("fail_code", "SELECT"),
              List.of("id", "SELECT"),
              List.of("order_number", "SELECT"),
              List.of("paid_at", "SELECT"),
              List.of("payment_expires_at", "SELECT"),
              List.of("product_id", "SELECT"),
              List.of("product_name", "SELECT"),
              List.of("quantity", "SELECT"),
              List.of("refunded_at", "SELECT"),
              List.of("status", "SELECT"),
              List.of("total_price", "SELECT"),
              List.of("unit_price", "SELECT"),
              List.of("updated_at", "SELECT"));
      assertThat(viewOwners)
          .containsExactlyInAnyOrder(
              List.of("v_drop_analytics", "ai_view_owner"),
              List.of("v_event_pipeline_analytics", "ai_view_owner"),
              List.of("v_member_current_snapshot", "ai_view_owner"),
              List.of("v_member_registration_analytics", "ai_view_owner"),
              List.of("v_order_analytics", "ai_view_owner"),
              List.of("v_order_current_saga", "ai_view_owner"),
              List.of("v_order_details", "ai_view_owner"),
              List.of("v_order_process_events", "ai_view_owner"),
              List.of("v_order_saga_analytics", "ai_view_owner"),
              List.of("v_order_status_transitions", "ai_view_owner"),
              List.of("v_payment_analytics", "ai_view_owner"),
              List.of("v_payment_pending_expirations", "ai_view_owner"),
              List.of("v_product_analytics", "ai_view_owner"),
              List.of("v_reconciliation_analytics", "ai_view_owner"),
              List.of("v_reconciliation_discrepancy_analytics", "ai_view_owner"),
              List.of("v_refund_analytics", "ai_view_owner"),
              List.of("v_seller_settlement_analytics", "ai_view_owner"),
              List.of("v_settlement_adjustment_analytics", "ai_view_owner"),
              List.of("v_settlement_batch_analytics", "ai_view_owner"),
              List.of("v_settlement_order_analytics", "ai_view_owner"),
              List.of("v_shared_unrelated", "ai_view_owner"));
      assertThat(membershipCount).isEqualTo("0");
      assertThat(connectionLimit).isEqualTo("4");
      assertThat(sensitiveColumnGrant).isEqualTo("f");
    }
  }

  @Test
  @DisplayName("실행 역할의 원본 SELECT·DML·DDL은 모두 거부된다")
  void queryRole_accessSource_rejectsReadWriteAndDdl() throws SQLException {
    // given
    Set<String> deniedStates = Set.of("42501", "25006");

    // when & then
    assertThatThrownBy(() -> executeAsQueryRole("SELECT created_at FROM orders.orders"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(() -> executeAsQueryRole("SELECT email FROM member.member"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(() -> executeAsQueryRole("SELECT pg_payment_key FROM payment.payments"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(() -> executeAsQueryRole("SELECT name FROM product.products"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(
            () -> executeAsQueryRole("SELECT seller_id FROM settlement.settlement_orders"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(() -> executeAsQueryRole("SELECT marker FROM ai_read.v_shared_unrelated"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(() -> executeAsQueryRole("DELETE FROM orders.orders"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(
            () -> executeAsQueryRole("ALTER TABLE orders.orders ADD COLUMN forbidden text"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
    assertThatThrownBy(() -> executeAsQueryRole("CREATE TABLE ai_read.forbidden(id integer)"))
        .isInstanceOf(SQLException.class)
        .extracting(error -> ((SQLException) error).getSQLState())
        .isIn(deniedStates);
  }

  @Test
  @DisplayName("신규 연결은 전용 역할과 read-only·3초·500ms 기본값을 사용한다")
  void queryRole_newConnection_appliesSessionLimits() throws SQLException {
    // given
    try (Connection connection = queryConnection();
        Statement statement = connection.createStatement()) {
      // when
      String currentUser = scalar(statement, "SELECT current_user");
      String defaultReadOnly = scalar(statement, "SHOW default_transaction_read_only");
      String transactionReadOnly = scalar(statement, "SHOW transaction_read_only");
      String statementTimeout = scalar(statement, "SHOW statement_timeout");
      String lockTimeout = scalar(statement, "SHOW lock_timeout");

      // then
      assertThat(currentUser).isEqualTo(QUERY_ROLE);
      assertThat(defaultReadOnly).isEqualTo("on");
      assertThat(transactionReadOnly).isEqualTo("on");
      assertThat(statementTimeout).isEqualTo("3s");
      assertThat(lockTimeout).isEqualTo("500ms");
    }
  }

  @Test
  @DisplayName("실행 역할은 앱 풀 세 연결과 배포 검증 한 연결까지만 허용한다")
  void queryRole_connectionBudget_allowsFourAndRejectsFifth() throws SQLException {
    // given
    try (Connection first = queryConnection();
        Connection second = queryConnection();
        Connection third = queryConnection();
        Connection verification = queryConnection()) {
      // when & then
      assertThat(first.isValid(1)).isTrue();
      assertThat(second.isValid(1)).isTrue();
      assertThat(third.isValid(1)).isTrue();
      assertThat(verification.isValid(1)).isTrue();
      assertThatThrownBy(AiReadModelIntegrationTest::queryConnection)
          .isInstanceOf(SQLException.class)
          .extracting(error -> ((SQLException) error).getSQLState())
          .isEqualTo("53300");
    }
  }

  @Test
  @DisplayName("배포 검증 SQL은 신규 실행 역할 연결에서 모든 계약을 통과한다")
  void verificationSql_queryRoleConnection_passes() throws Exception {
    // given
    ClassPathResource resource = new ClassPathResource("db/read-model/verify-ai-read-model.sql");
    String sql =
        resource.getContentAsString(StandardCharsets.UTF_8).replace("\\set ON_ERROR_STOP on", "");

    // when & then
    try (Connection connection = queryConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Test
  @DisplayName("같은 DDL을 다시 적용해도 권한과 비밀번호가 유지된다")
  void applyReadModel_reapplied_remainsUsable() throws Exception {
    // given
    String ddl = renderDdl(TEST_QUERY_PASSWORD);

    // when
    applyReadModel(ddl);

    // then
    try (Connection connection = queryConnection()) {
      assertThat(rows(connection, "SELECT status FROM ai_read.v_order_analytics")).hasSize(8);
    }
  }

  @Test
  @DisplayName("재적용 중 오류가 나면 기존 뷰와 자격 증명을 원자적으로 보존한다")
  void applyReadModel_failed_reapplicationRollsBack() throws Exception {
    // given
    String brokenDdl =
        renderDdl(TEST_QUERY_PASSWORD)
            .replaceFirst("FROM orders\\.orders", "FROM orders.missing_orders");

    // when & then
    assertThatThrownBy(() -> applyReadModel(brokenDdl)).isInstanceOf(SQLException.class);
    try (Connection connection = queryConnection()) {
      assertThat(rows(connection, "SELECT status FROM ai_read.v_order_analytics")).hasSize(8);
    }
  }

  @Test
  @DisplayName("기동 검증기는 올바른 실행 역할만 데이터 capability로 허용한다")
  void verifier_accountBoundary_failsClosedForWrongAccount() {
    // given
    ChatQueryDataSourceProperties validProperties =
        queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD);
    ChatQueryDataSourceProperties wrongProperties =
        queryProperties(postgres.getUsername(), postgres.getPassword());

    // when
    Availability validAvailability = verify(validProperties);
    Availability wrongAvailability = verify(wrongProperties);

    // then
    assertThat(validAvailability).isEqualTo(Availability.AVAILABLE);
    assertThat(wrongAvailability).isEqualTo(Availability.UNAVAILABLE);
  }

  @Test
  @DisplayName("임의 역할 멤버십이 생기면 기동 검증은 닫히고 DDL 재적용이 멤버십을 제거한다")
  void verifier_roleMembershipDrift_failsClosedAndReapplicationRepairs() throws Exception {
    // given
    executeAdmin("CREATE ROLE ai_query_membership_drift");
    executeAdmin("GRANT ai_query_membership_drift TO ai_query_app");

    try {
      // when & then
      assertThat(verify(queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD)))
          .isEqualTo(Availability.UNAVAILABLE);

      // when
      applyReadModel(renderDdl(TEST_QUERY_PASSWORD));

      // then
      try (Connection connection = adminConnection();
          Statement statement = connection.createStatement()) {
        assertThat(
                scalar(
                    statement,
                    """
                    SELECT count(*)
                    FROM pg_catalog.pg_auth_members membership
                    JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
                    WHERE member_role.rolname = 'ai_query_app'
                    """))
            .isEqualTo("0");
      }
      assertThat(verify(queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD)))
          .isEqualTo(Availability.AVAILABLE);
    } finally {
      executeAdmin("DROP ROLE IF EXISTS ai_query_membership_drift");
    }
  }

  @Test
  @DisplayName("승인 뷰 밖의 직접 컬럼 권한이 생기면 기동 검증은 닫힌다")
  void verifier_directColumnPrivilegeDrift_failsClosed() throws SQLException {
    // given
    executeAdmin("CREATE TABLE public.ai_query_privilege_drift(id integer, secret text)");
    executeAdmin("GRANT SELECT (id) ON public.ai_query_privilege_drift TO ai_query_app");

    try {
      // when & then
      assertThat(verify(queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD)))
          .isEqualTo(Availability.UNAVAILABLE);
    } finally {
      executeAdmin("DROP TABLE IF EXISTS public.ai_query_privilege_drift");
    }

    assertThat(verify(queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD)))
        .isEqualTo(Availability.AVAILABLE);
  }

  @Test
  @DisplayName("개별 주문 함수가 PUBLIC에 열리면 기동 검증은 닫힌다")
  void verifier_publicFunctionPrivilegeDrift_failsClosed() throws SQLException {
    // given
    executeAdmin(
        """
        GRANT EXECUTE
        ON FUNCTION ai_read.lookup_order_detail(character varying)
        TO PUBLIC
        """);

    try {
      // when & then
      assertThat(verify(queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD)))
          .isEqualTo(Availability.UNAVAILABLE);
    } finally {
      executeAdmin(
          """
          REVOKE EXECUTE
          ON FUNCTION ai_read.lookup_order_detail(character varying)
          FROM PUBLIC
          """);
    }

    assertThat(verify(queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD)))
        .isEqualTo(Availability.AVAILABLE);
  }

  @Test
  @DisplayName("구조 preflight가 실패하면 회전하려던 비밀번호도 같은 transaction에서 되돌린다")
  void applyReadModel_privilegeDrift_rollsBackPasswordWithStructure() throws Exception {
    // given
    executeAdmin("CREATE TABLE public.ai_query_rotation_drift(id integer)");
    executeAdmin("GRANT SELECT (id) ON public.ai_query_rotation_drift TO ai_query_app");

    try {
      // when & then
      assertThatThrownBy(() -> applyReadModel(renderDdl("rotated-password-must-not-be-committed")))
          .isInstanceOf(SQLException.class);
      try (Connection connection = queryConnection()) {
        assertThat(rows(connection, "SELECT status FROM ai_read.v_order_analytics")).hasSize(8);
      }
    } finally {
      executeAdmin("DROP TABLE IF EXISTS public.ai_query_rotation_drift");
    }
  }

  @Test
  @DisplayName("실제 JDBC 어댑터의 고정 지표는 만료 경계와 삭제·상태·null 조건을 지킨다")
  void jdbcAdapter_expiredPaymentMetric_appliesExactViewSemantics() {
    // given
    Instant expirationBoundary = Instant.parse("2026-07-21T15:10:00Z");

    try (QueryHarness atBoundaryHarness = queryHarness(expirationBoundary);
        QueryHarness afterBoundaryHarness = queryHarness(expirationBoundary.plusSeconds(1));
        QueryHarness afterDeletedHarness = queryHarness(Instant.parse("2026-07-21T18:00:00Z"))) {
      // when
      AdminDataQueryResult.Metric atBoundary =
          atBoundaryHarness.adapter().countExpiredPaymentPendingOrders();
      AdminDataQueryResult.Metric afterBoundary =
          afterBoundaryHarness.adapter().countExpiredPaymentPendingOrders();
      AdminDataQueryResult.Metric afterDeletedOrderExpiration =
          afterDeletedHarness.adapter().countExpiredPaymentPendingOrders();

      // then
      assertThat(atBoundary.value()).isZero();
      assertThat(atBoundary.asOf()).isEqualTo(expirationBoundary);
      assertThat(afterBoundary.value()).isEqualTo(1L);
      assertThat(afterDeletedOrderExpiration.value()).isEqualTo(1L);
    }
  }

  @Test
  @DisplayName("실제 분석 어댑터는 전체·상태·일자·월간 집계를 KST 반개방 구간으로 계산한다")
  void jdbcAdapter_semanticPlans_aggregateWithKstBoundaries() {
    // given
    PlanningDateRange today =
        range(Instant.parse("2026-07-21T15:00:00Z"), Instant.parse("2026-07-22T15:00:00Z"));
    PlanningDateRange recentDays =
        range(Instant.parse("2026-07-20T15:00:00Z"), Instant.parse("2026-07-22T15:00:00Z"));
    PlanningDateRange month =
        range(Instant.parse("2026-06-30T15:00:00Z"), Instant.parse("2026-07-31T15:00:00Z"));

    try (QueryHarness harness = queryHarness()) {
      // when
      AdminAnalyticsQueryResult total =
          harness.analyticsAdapter().query(orderQuery(List.of(), today, TrendGrain.NONE));
      AdminAnalyticsQueryResult byStatus =
          harness
              .analyticsAdapter()
              .query(orderQuery(List.of(Dimension.STATUS), today, TrendGrain.NONE));
      AdminAnalyticsQueryResult byDayAndStatus =
          harness
              .analyticsAdapter()
              .query(orderQuery(List.of(Dimension.STATUS), recentDays, TrendGrain.DAY));
      AdminAnalyticsQueryResult monthByStatus =
          harness
              .analyticsAdapter()
              .query(orderQuery(List.of(Dimension.STATUS), month, TrendGrain.NONE));

      // then
      assertThat(total.rows())
          .extracting(
              AdminAnalyticsQueryResult.Row::bucketStart,
              row -> row.dimensions().get("STATUS"),
              row -> row.measures().get("ORDER_COUNT"))
          .containsExactly(tuple(null, null, new java.math.BigDecimal("3")));
      assertThat(total.asOf()).isEqualTo(Instant.parse("2026-07-22T03:00:00Z"));
      assertThat(byStatus.rows())
          .extracting(
              row -> row.dimensions().get("STATUS"), row -> row.measures().get("ORDER_COUNT"))
          .containsExactly(
              tuple("PAYMENT_PENDING", new java.math.BigDecimal("2")),
              tuple("COMPLETED", new java.math.BigDecimal("1")));
      assertThat(byDayAndStatus.rows())
          .extracting(
              AdminAnalyticsQueryResult.Row::bucketStart,
              row -> row.dimensions().get("STATUS"),
              row -> row.measures().get("ORDER_COUNT"))
          .containsExactly(
              tuple(Instant.parse("2026-07-20T15:00:00Z"), "FAILED", new java.math.BigDecimal("1")),
              tuple(
                  Instant.parse("2026-07-21T15:00:00Z"),
                  "COMPLETED",
                  new java.math.BigDecimal("1")),
              tuple(
                  Instant.parse("2026-07-21T15:00:00Z"),
                  "PAYMENT_PENDING",
                  new java.math.BigDecimal("2")));
      assertThat(monthByStatus.rows())
          .extracting(
              row -> row.dimensions().get("STATUS"), row -> row.measures().get("ORDER_COUNT"))
          .containsExactly(
              tuple("PAYMENT_PENDING", new java.math.BigDecimal("2")),
              tuple("CANCELLED", new java.math.BigDecimal("1")),
              tuple("COMPLETED", new java.math.BigDecimal("1")),
              tuple("FAILED", new java.math.BigDecimal("1")),
              tuple("REFUND_PENDING", new java.math.BigDecimal("1")));
    }
  }

  @Test
  @DisplayName("실제 JDBC 어댑터는 빈 기간의 그룹 집계와 전체 건수를 구분한다")
  void jdbcAdapter_emptyPeriod_returnsEmptyGroupsAndZeroTotal() {
    // given
    PlanningDateRange emptyPeriod =
        range(Instant.parse("2026-05-01T15:00:00Z"), Instant.parse("2026-05-02T15:00:00Z"));

    try (QueryHarness harness = queryHarness()) {
      // when
      AdminAnalyticsQueryResult grouped =
          harness
              .analyticsAdapter()
              .query(orderQuery(List.of(Dimension.STATUS), emptyPeriod, TrendGrain.NONE));
      AdminAnalyticsQueryResult total =
          harness.analyticsAdapter().query(orderQuery(List.of(), emptyPeriod, TrendGrain.NONE));

      // then
      assertThat(grouped.rows()).isEmpty();
      assertThat(total.rows())
          .extracting(row -> row.measures().get("ORDER_COUNT"))
          .containsExactly(new java.math.BigDecimal("0"));
    }
  }

  @Test
  @DisplayName("개별 상품 조회는 가격 미설정을 0원이 아닌 null로 보존한다")
  void jdbcAdapter_productRows_preserveUnconfiguredPrice() {
    Query query =
        new Query(
            Dataset.PRODUCT,
            List.of(Measure.PRODUCT_PRICE),
            List.of(Dimension.PRODUCT_ID, Dimension.PRODUCT_NAME),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.PRODUCT_PRICE,
            SortDirection.DESC,
            20);

    try (QueryHarness harness = queryHarness()) {
      AdminAnalyticsQueryResult result = harness.analyticsAdapter().query(query);

      assertThat(result.rows())
          .filteredOn(row -> "Private Product Name 2".equals(row.dimensions().get("PRODUCT_NAME")))
          .singleElement()
          .satisfies(row -> assertThat(row.measures().get("PRODUCT_PRICE")).isNull());
    }
  }

  @Test
  @DisplayName("실제 분석 어댑터는 현재 사가 지연과 미처리 이벤트를 스냅샷으로 집계한다")
  void jdbcAdapter_reliabilitySnapshot_aggregatesWithoutPeriod() {
    Query saga =
        new Query(
            Dataset.ORDER_SAGA,
            List.of(Measure.STALLED_SAGA_COUNT),
            List.of(),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.STALLED_SAGA_COUNT,
            SortDirection.DESC,
            10);
    Query events =
        new Query(
            Dataset.EVENT_PIPELINE,
            List.of(Measure.PENDING_EVENT_COUNT),
            List.of(),
            TimeField.NONE,
            AggregateTimeScope.CURRENT_SNAPSHOT,
            null,
            TrendGrain.NONE,
            Comparison.NONE,
            Map.of(),
            Measure.PENDING_EVENT_COUNT,
            SortDirection.DESC,
            10);

    try (QueryHarness harness = queryHarness()) {
      AdminAnalyticsQueryResult sagaResult = harness.analyticsAdapter().query(saga);
      AdminAnalyticsQueryResult eventResult = harness.analyticsAdapter().query(events);

      assertThat(sagaResult.rows())
          .extracting(row -> row.measures().get("STALLED_SAGA_COUNT"))
          .containsExactly(new java.math.BigDecimal("0"));
      assertThat(eventResult.rows())
          .extracting(row -> row.measures().get("PENDING_EVENT_COUNT"))
          .containsExactly(new java.math.BigDecimal("1"));
    }
  }

  @Test
  @DisplayName("작은 fixture에서도 고정 지표와 의미 집계 SQL의 실제 실행계획을 확인한다")
  void jdbcAdapter_queries_explainAnalyzeOnFixture() {
    // given
    PlanningDateRange today =
        range(Instant.parse("2026-07-21T15:00:00Z"), Instant.parse("2026-07-22T15:00:00Z"));

    try (QueryHarness harness = queryHarness()) {
      Query plan = orderQuery(List.of(Dimension.STATUS), today, TrendGrain.NONE);
      JdbcAdminAnalyticsQueryAdapter.CompiledQuery aggregateQuery =
          harness.analyticsAdapter().compile(plan, today, Instant.parse("2026-07-22T03:00:00Z"));

      // when
      List<String> fixedMetricPlan =
          harness
              .jdbcTemplate()
              .query(
                  """
                  EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
                  SELECT COUNT(*) AS order_count
                  FROM ai_read.v_payment_pending_expirations
                  WHERE payment_expires_at < :asOf
                  """,
                  Map.of("asOf", Timestamp.from(Instant.parse("2026-07-22T03:00:00Z"))),
                  (resultSet, rowNumber) -> resultSet.getString(1));
      List<String> aggregatePlan =
          harness
              .jdbcTemplate()
              .query(
                  "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + aggregateQuery.sql(),
                  aggregateQuery.parameters(),
                  (resultSet, rowNumber) -> resultSet.getString(1));

      // then
      assertThat(String.join("\n", fixedMetricPlan))
          .contains("Aggregate")
          .contains("Planning Time")
          .contains("Execution Time");
      assertThat(String.join("\n", aggregatePlan))
          .contains("Limit")
          .contains("Planning Time")
          .contains("Execution Time");
    }
  }

  @Test
  @DisplayName("적용 도구는 비밀번호를 인자로 받지 않고 단일 트랜잭션으로 실행한다")
  void applyScript_contract_keepsSecretsOutOfArguments() throws IOException {
    // given
    String script =
        Files.readString(Path.of("ai", "scripts", "apply-read-model.sh"), StandardCharsets.UTF_8);

    // when & then
    assertThat(script)
        .contains("--single-transaction")
        .contains("\\getenv AI_QUERY_DB_PASSWORD AI_QUERY_DB_PASSWORD")
        .contains("AI_QUERY_DB_PREVIOUS_PASSWORD")
        .contains("ALTER ROLE ai_query_app PASSWORD :'AI_QUERY_DB_PREVIOUS_PASSWORD'")
        .doesNotContain("--password=")
        .doesNotContain("--set=AI_QUERY_DB_PASSWORD");
  }

  private static void createSourceTables() throws SQLException {
    String sql =
        """
        CREATE SCHEMA orders;
        CREATE SCHEMA member;
        CREATE SCHEMA payment;
        CREATE SCHEMA product;
        CREATE SCHEMA settlement;

        CREATE TABLE orders.orders (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            order_number character varying(30) NOT NULL UNIQUE,
            product_id uuid,
            product_name character varying(255) NOT NULL DEFAULT 'Fixture Product',
            quantity integer NOT NULL DEFAULT 1,
            created_at timestamp with time zone NOT NULL,
            updated_at timestamp with time zone NOT NULL,
            status character varying(30) NOT NULL,
            fail_code character varying(50),
            payment_expires_at timestamp with time zone,
            paid_at timestamp with time zone,
            completed_at timestamp with time zone,
            cancelled_at timestamp with time zone,
            refunded_at timestamp with time zone,
            deleted_at timestamp with time zone,
            member_id uuid,
            total_price bigint,
            unit_price bigint,
            fail_message character varying(255),
            saga_id character varying(64)
        );
        CREATE TABLE orders.order_histories (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            order_id uuid NOT NULL,
            previous_status character varying(30),
            new_status character varying(30) NOT NULL,
            reason_code character varying(50),
            reason_message character varying(255),
            source_event_key character varying(100),
            created_at timestamp with time zone NOT NULL
        );
        CREATE TABLE orders.order_saga_states (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            order_id uuid NOT NULL UNIQUE,
            saga_id character varying(64) NOT NULL,
            current_step character varying(50) NOT NULL,
            compensating_since timestamp with time zone,
            created_at timestamp with time zone NOT NULL,
            updated_at timestamp with time zone NOT NULL
        );
        CREATE TABLE orders.inbox_events (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            event_id character varying(100) NOT NULL,
            event_type character varying(100) NOT NULL,
            payload text NOT NULL,
            status character varying(20) NOT NULL,
            error_message character varying(500),
            created_at timestamp with time zone NOT NULL
        );
        CREATE TABLE orders.outbox_events (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            topic character varying(100) NOT NULL,
            payload text NOT NULL,
            status character varying(20) NOT NULL,
            created_at timestamp with time zone NOT NULL
        );

        CREATE TABLE member.member (
            id uuid PRIMARY KEY,
            platform_type character varying(30) NOT NULL,
            email character varying(255) NOT NULL,
            nickname character varying(255) NOT NULL,
            created_at timestamp without time zone NOT NULL,
            deleted_at timestamp without time zone
        );
        CREATE TABLE member.role (
            id bigint PRIMARY KEY,
            role character varying(30) NOT NULL
        );
        CREATE TABLE member.role_history (
            id bigint PRIMARY KEY,
            member_id uuid NOT NULL,
            role_id bigint NOT NULL,
            created_at timestamp without time zone NOT NULL,
            deleted_at timestamp without time zone
        );
        CREATE TABLE member.wishlist_item (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            member_id uuid NOT NULL,
            product_id uuid NOT NULL
        );
        CREATE TABLE member.outbox_events (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            aggregate_type character varying(30) NOT NULL,
            aggregate_id uuid NOT NULL,
            topic character varying(100) NOT NULL,
            payload text NOT NULL,
            status character varying(20) NOT NULL,
            created_at timestamp without time zone NOT NULL
        );

        CREATE TABLE payment.payments (
            id uuid PRIMARY KEY,
            order_id uuid NOT NULL,
            member_id uuid NOT NULL,
            seller_id uuid,
            product_id uuid,
            amount bigint NOT NULL,
            method character varying(20) NOT NULL,
            pg_provider character varying(20),
            pg_payment_key character varying(500),
            pg_payment_key_hash character varying(64),
            pg_tx_id character varying(100),
            status character varying(20) NOT NULL,
            refunded_amount bigint NOT NULL,
            pg_recon_status character varying(20) NOT NULL,
            idempotency_key character varying(100) NOT NULL,
            request_hash character varying(64),
            approved_at timestamp without time zone,
            created_at timestamp without time zone NOT NULL
        );
        CREATE TABLE payment.refunds (
            id uuid PRIMARY KEY,
            payment_id uuid NOT NULL,
            amount bigint NOT NULL,
            status character varying(20) NOT NULL,
            reason character varying(255),
            pg_refund_key character varying(500),
            idempotency_key character varying(100) NOT NULL,
            request_hash character varying(64),
            completed_at timestamp without time zone,
            created_at timestamp without time zone NOT NULL,
            pg_recon_status character varying(20) NOT NULL
        );
        CREATE TABLE payment.outbox_events (
            id uuid PRIMARY KEY,
            aggregate_type character varying(30) NOT NULL,
            aggregate_id uuid NOT NULL,
            topic character varying(100) NOT NULL,
            payload text NOT NULL,
            status character varying(20) NOT NULL,
            created_at timestamp without time zone NOT NULL
        );

        CREATE TABLE product.categories (
            id uuid PRIMARY KEY,
            name character varying(50) NOT NULL
        );
        CREATE TABLE product.products (
            id uuid PRIMARY KEY,
            seller_id uuid NOT NULL,
            name character varying(100) NOT NULL,
            category_id uuid,
            description text,
            price bigint,
            thumbnail_key character varying(512),
            created_at timestamp with time zone NOT NULL,
            deleted_at timestamp with time zone
        );
        CREATE TABLE product.product_images (
            product_id uuid NOT NULL,
            image_order integer NOT NULL,
            image_key character varying(512) NOT NULL
        );
        CREATE TABLE product.drops (
            id uuid PRIMARY KEY,
            product_id uuid NOT NULL,
            drop_price bigint NOT NULL,
            total_quantity integer NOT NULL,
            limit_per_user integer,
            open_at timestamp with time zone NOT NULL,
            close_at timestamp with time zone,
            status character varying(20) NOT NULL,
            created_at timestamp with time zone NOT NULL,
            deleted_at timestamp with time zone
        );
        CREATE TABLE product.stock_histories (
            id uuid PRIMARY KEY,
            drop_id uuid NOT NULL,
            order_id uuid NOT NULL,
            change_type character varying(20) NOT NULL,
            quantity_delta integer NOT NULL
        );

        CREATE TABLE settlement.settlement_orders (
            settlement_order_id uuid PRIMARY KEY,
            seller_settlement_id uuid,
            payment_id uuid NOT NULL,
            order_id uuid NOT NULL,
            seller_id uuid NOT NULL,
            buyer_id uuid NOT NULL,
            product_id uuid NOT NULL,
            settlement_month character varying(6) NOT NULL,
            order_amount bigint NOT NULL,
            paid_amount bigint NOT NULL,
            fee_amount bigint NOT NULL,
            refund_amount bigint NOT NULL,
            net_settlement_amount bigint NOT NULL,
            settlement_status character varying(20) NOT NULL,
            paid_at timestamp without time zone NOT NULL
        );
        CREATE TABLE settlement.seller_settlements (
            seller_settlement_id uuid PRIMARY KEY,
            batch_id uuid NOT NULL,
            settlement_month character varying(6) NOT NULL,
            seller_id uuid NOT NULL,
            total_order_count integer NOT NULL,
            total_paid_amount bigint NOT NULL,
            total_fee_amount bigint NOT NULL,
            total_refund_amount bigint NOT NULL,
            total_adjustment_amount bigint NOT NULL,
            final_settlement_amount bigint NOT NULL,
            status character varying(20) NOT NULL,
            fail_reason character varying(500)
        );
        CREATE TABLE settlement.settlement_batchs (
            batch_id uuid PRIMARY KEY,
            settlement_month character varying(6) NOT NULL,
            batch_type character varying(30) NOT NULL,
            status character varying(20) NOT NULL,
            started_at timestamp without time zone,
            ended_at timestamp without time zone,
            total_order_count integer NOT NULL,
            total_seller_count integer NOT NULL,
            total_settlement_amount bigint NOT NULL,
            fail_reason character varying(500),
            created_at timestamp without time zone NOT NULL
        );
        CREATE TABLE settlement.settlement_adjustments (
            adjustment_id uuid PRIMARY KEY,
            seller_id uuid NOT NULL,
            order_id uuid NOT NULL,
            refund_id uuid NOT NULL,
            settlement_month character varying(6) NOT NULL,
            adjustment_type character varying(30) NOT NULL,
            adjustment_amount bigint NOT NULL,
            status character varying(20) NOT NULL,
            reason character varying(500)
        );
        CREATE TABLE settlement.daily_reconciliation_results (
            id uuid PRIMARY KEY,
            business_date date NOT NULL,
            status character varying(20) NOT NULL,
            payment_count integer NOT NULL,
            total_payment_amount bigint NOT NULL,
            refund_count integer NOT NULL,
            total_refund_amount bigint NOT NULL,
            expected_settlement_amount bigint NOT NULL,
            discrepancy_count integer NOT NULL,
            executed_at timestamp without time zone NOT NULL
        );
        CREATE TABLE settlement.daily_reconciliation_discrepancies (
            id uuid PRIMARY KEY,
            business_date date NOT NULL,
            entity_type character varying(20) NOT NULL,
            reference_id uuid NOT NULL,
            discrepancy_type character varying(30) NOT NULL,
            detail character varying(500),
            created_at timestamp without time zone NOT NULL
        );
        """;
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void createSharedReadModelView() throws SQLException {
    String sql =
        """
        CREATE ROLE ai_view_owner
            NOLOGIN
            NOINHERIT
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            NOREPLICATION
            NOBYPASSRLS;
        CREATE SCHEMA ai_read AUTHORIZATION ai_view_owner;
        SET ROLE ai_view_owner;
        CREATE VIEW ai_read.v_shared_unrelated AS SELECT 1 AS marker;
        RESET ROLE;
        """;
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void seedOrders() throws SQLException {
    String sql =
        """
        INSERT INTO orders.orders(
            order_number,
            created_at,
            updated_at,
            status,
            payment_expires_at,
            deleted_at
        )
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = adminConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      addOrder(
          statement,
          "ORD-FIXTURE-001",
          Instant.parse("2026-06-30T14:59:59Z"),
          "REFUNDED",
          null,
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-002",
          Instant.parse("2026-06-30T15:00:00Z"),
          "REFUND_PENDING",
          null,
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-003",
          Instant.parse("2026-07-21T14:59:59Z"),
          "FAILED",
          null,
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-004",
          Instant.parse("2026-07-21T15:00:00Z"),
          "PAYMENT_PENDING",
          Instant.parse("2026-07-21T15:10:00Z"),
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-005",
          Instant.parse("2026-07-21T16:00:00Z"),
          "PAYMENT_PENDING",
          Instant.parse("2026-07-21T16:10:00Z"),
          Instant.parse("2026-07-21T17:00:00Z"));
      addOrder(
          statement,
          "ORD-FIXTURE-006",
          Instant.parse("2026-07-21T18:00:00Z"),
          "COMPLETED",
          null,
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-007",
          Instant.parse("2026-07-21T19:00:00Z"),
          "PAYMENT_PENDING",
          null,
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-008",
          Instant.parse("2026-07-22T15:00:00Z"),
          "CANCELLED",
          null,
          null);
      addOrder(
          statement,
          "ORD-FIXTURE-009",
          Instant.parse("2026-07-31T15:00:00Z"),
          "FAILED",
          null,
          null);
      statement.executeBatch();
    }

    executeAdmin(
        """
        UPDATE orders.orders
        SET product_name = 'Exact Product',
            product_id = '40000000-0000-0000-0000-000000000001',
            quantity = 2,
            member_id = '90000000-0000-0000-0000-000000000001',
            total_price = 20000,
            unit_price = 10000,
            paid_at = '2026-07-21T18:01:00Z',
            completed_at = '2026-07-21T18:02:00Z',
            saga_id = 'private-saga-id'
        WHERE order_number = 'ORD-FIXTURE-006';

        UPDATE orders.orders
        SET fail_code = 'PAYMENT_FAILED',
            fail_message = 'private payment failure detail'
        WHERE order_number = 'ORD-FIXTURE-003';

        INSERT INTO orders.order_histories(
            order_id,
            previous_status,
            new_status,
            reason_code,
            reason_message,
            source_event_key,
            created_at
        )
        VALUES
        (
            (SELECT id FROM orders.orders WHERE order_number = 'ORD-FIXTURE-006'),
            NULL,
            'PAYMENT_PENDING',
            'ORDER_CREATED',
            'private creation detail',
            'private-source-event-1',
            '2026-07-21T18:00:00Z'
        ),
        (
            (SELECT id FROM orders.orders WHERE order_number = 'ORD-FIXTURE-006'),
            'PAYMENT_PENDING',
            'COMPLETED',
            'PAYMENT_APPROVED',
            'private payment detail',
            'private-source-event-2',
            '2026-07-21T18:02:00Z'
        ),
        (
            (SELECT id FROM orders.orders WHERE order_number = 'ORD-FIXTURE-003'),
            NULL,
            'FAILED',
            'ORDER_CREATED',
            'other order private detail',
            'other-private-source-event',
            '2026-07-21T14:59:59Z'
        );

        INSERT INTO orders.order_saga_states(
            order_id,
            saga_id,
            current_step,
            compensating_since,
            created_at,
            updated_at
        )
        VALUES (
            (SELECT id FROM orders.orders WHERE order_number = 'ORD-FIXTURE-006'),
            'private-saga-id',
            'PAYMENT_COMPLETED',
            NULL,
            '2026-07-21T18:00:00Z',
            '2026-07-21T18:02:00Z'
        );
        """);
  }

  private static void addOrder(
      PreparedStatement statement,
      String orderNumber,
      Instant createdAt,
      String status,
      Instant paymentExpiresAt,
      Instant deletedAt)
      throws SQLException {
    statement.setString(1, orderNumber);
    statement.setTimestamp(2, Timestamp.from(createdAt));
    statement.setTimestamp(3, Timestamp.from(createdAt));
    statement.setString(4, status);
    statement.setTimestamp(5, timestamp(paymentExpiresAt));
    statement.setTimestamp(6, timestamp(deletedAt));
    statement.addBatch();
  }

  private static void seedMemberAndProductFacts() throws SQLException {
    executeAdmin(
        """
        INSERT INTO member.role(id, role)
        VALUES (1, 'ROLE_USER'), (2, 'ROLE_SELLER');

        INSERT INTO member.member(id, platform_type, email, nickname, created_at, deleted_at)
        VALUES
        (
            '20000000-0000-0000-0000-000000000001',
            'LOCAL',
            'private-local@example.com',
            'private-local',
            '2026-07-19T10:00:00',
            NULL
        ),
        (
            '20000000-0000-0000-0000-000000000002',
            'KAKAO',
            'private-kakao@example.com',
            'private-kakao',
            '2026-07-20T11:00:00',
            NULL
        ),
        (
            '20000000-0000-0000-0000-000000000003',
            'GOOGLE',
            'private-google@example.com',
            'private-google',
            '2026-07-20T12:00:00',
            NULL
        ),
        (
            '20000000-0000-0000-0000-000000000004',
            'NAVER',
            'private-withdrawn@example.com',
            'private-withdrawn',
            '2026-07-18T10:00:00',
            '2026-07-20T00:00:00'
        );

        INSERT INTO member.role_history(id, member_id, role_id, created_at, deleted_at)
        VALUES
        (
            1,
            '20000000-0000-0000-0000-000000000001',
            1,
            '2026-07-01T00:00:00',
            NULL
        ),
        (
            2,
            '20000000-0000-0000-0000-000000000002',
            1,
            '2026-07-01T00:00:00',
            NULL
        ),
        (
            3,
            '20000000-0000-0000-0000-000000000002',
            2,
            '2026-07-02T00:00:00',
            NULL
        ),
        (
            4,
            '20000000-0000-0000-0000-000000000004',
            1,
            '2026-07-01T00:00:00',
            NULL
        );

        INSERT INTO product.categories(id, name)
        VALUES ('30000000-0000-0000-0000-000000000001', 'Fashion');

        INSERT INTO product.products(
            id,
            seller_id,
            name,
            category_id,
            description,
            price,
            thumbnail_key,
            created_at,
            deleted_at
        )
        VALUES
        (
            '40000000-0000-0000-0000-000000000001',
            '50000000-0000-0000-0000-000000000001',
            'Private Product Name 1',
            '30000000-0000-0000-0000-000000000001',
            'configured description',
            10000,
            NULL,
            '2026-07-01T00:00:00Z',
            NULL
        ),
        (
            '40000000-0000-0000-0000-000000000002',
            '50000000-0000-0000-0000-000000000002',
            'Private Product Name 2',
            NULL,
            '   ',
            NULL,
            NULL,
            '2026-07-02T00:00:00Z',
            NULL
        ),
        (
            '40000000-0000-0000-0000-000000000003',
            '50000000-0000-0000-0000-000000000003',
            'Deleted Product',
            NULL,
            'configured deleted description',
            30000,
            'private/deleted/thumbnail',
            '2026-07-02T00:00:00Z',
            '2026-07-03T00:00:00Z'
        );

        INSERT INTO product.product_images(product_id, image_order, image_key)
        VALUES (
            '40000000-0000-0000-0000-000000000001',
            0,
            'private/image/key'
        );

        INSERT INTO member.wishlist_item(id, member_id, product_id)
        VALUES
        (
            '21000000-0000-0000-0000-000000000001',
            '20000000-0000-0000-0000-000000000001',
            '40000000-0000-0000-0000-000000000001'
        ),
        (
            '21000000-0000-0000-0000-000000000002',
            '20000000-0000-0000-0000-000000000002',
            '40000000-0000-0000-0000-000000000001'
        ),
        (
            '21000000-0000-0000-0000-000000000003',
            '20000000-0000-0000-0000-000000000003',
            '40000000-0000-0000-0000-000000000002'
        );

        INSERT INTO product.drops(
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
        )
        VALUES
        (
            '60000000-0000-0000-0000-000000000001',
            '40000000-0000-0000-0000-000000000001',
            9000,
            10,
            1,
            '2026-07-10T00:00:00Z',
            NULL,
            'REGISTERED',
            '2026-07-01T00:00:00Z',
            NULL
        ),
        (
            '60000000-0000-0000-0000-000000000002',
            '40000000-0000-0000-0000-000000000002',
            19000,
            5,
            NULL,
            '2026-07-11T00:00:00Z',
            '2026-07-12T00:00:00Z',
            'CLOSE',
            '2026-07-02T00:00:00Z',
            NULL
        ),
        (
            '60000000-0000-0000-0000-000000000003',
            '40000000-0000-0000-0000-000000000002',
            19000,
            5,
            NULL,
            '2026-07-13T00:00:00Z',
            NULL,
            'REGISTERED',
            '2026-07-03T00:00:00Z',
            '2026-07-04T00:00:00Z'
        ),
        (
            '60000000-0000-0000-0000-000000000004',
            '40000000-0000-0000-0000-000000000003',
            29000,
            3,
            1,
            '2026-07-13T00:00:00Z',
            NULL,
            'REGISTERED',
            '2026-07-03T00:00:00Z',
            NULL
        );

        INSERT INTO product.stock_histories(id, drop_id, order_id, change_type, quantity_delta)
        VALUES
        (
            '70000000-0000-0000-0000-000000000001',
            '60000000-0000-0000-0000-000000000001',
            '80000000-0000-0000-0000-000000000001',
            'DEDUCT',
            -10
        ),
        (
            '70000000-0000-0000-0000-000000000002',
            '60000000-0000-0000-0000-000000000002',
            '80000000-0000-0000-0000-000000000002',
            'DEDUCT',
            -2
        ),
        (
            '70000000-0000-0000-0000-000000000003',
            '60000000-0000-0000-0000-000000000002',
            '80000000-0000-0000-0000-000000000002',
            'ROLLBACK',
            1
        );
        """);
  }

  private static void seedExtendedAnalyticsFacts() throws SQLException {
    executeAdmin(
        """
        INSERT INTO payment.payments(
            id,
            order_id,
            member_id,
            seller_id,
            product_id,
            amount,
            method,
            pg_provider,
            pg_payment_key,
            pg_payment_key_hash,
            pg_tx_id,
            status,
            refunded_amount,
            pg_recon_status,
            idempotency_key,
            request_hash,
            approved_at,
            created_at
        )
        VALUES
        (
            '91000000-0000-0000-0000-000000000001',
            '92000000-0000-0000-0000-000000000001',
            '20000000-0000-0000-0000-000000000001',
            '50000000-0000-0000-0000-000000000001',
            '40000000-0000-0000-0000-000000000001',
            10000,
            'PG',
            'TOSS',
            'private-payment-key',
            'private-payment-key-hash',
            'private-pg-transaction',
            'APPROVED',
            3000,
            'MATCHED',
            'private-payment-idempotency',
            'private-payment-request-hash',
            '2026-07-21T10:16:00',
            '2026-07-21T10:15:00'
        ),
        (
            '91000000-0000-0000-0000-000000000002',
            '92000000-0000-0000-0000-000000000002',
            '20000000-0000-0000-0000-000000000002',
            '50000000-0000-0000-0000-000000000002',
            '40000000-0000-0000-0000-000000000002',
            20000,
            'WALLET',
            NULL,
            NULL,
            NULL,
            NULL,
            'FAILED',
            0,
            'NOT_CHECKED',
            'private-wallet-idempotency',
            'private-wallet-request-hash',
            NULL,
            '2026-07-21T11:15:00'
        );

        INSERT INTO payment.refunds(
            id,
            payment_id,
            amount,
            status,
            reason,
            pg_refund_key,
            idempotency_key,
            request_hash,
            completed_at,
            created_at,
            pg_recon_status
        )
        VALUES
        (
            '93000000-0000-0000-0000-000000000001',
            '91000000-0000-0000-0000-000000000001',
            3000,
            'COMPLETE',
            'private refund reason',
            'private-refund-key',
            'private-refund-idempotency',
            'private-refund-request-hash',
            '2026-07-21T12:05:00',
            '2026-07-21T12:00:00',
            'MATCHED'
        );

        INSERT INTO settlement.settlement_orders(
            settlement_order_id,
            seller_settlement_id,
            payment_id,
            order_id,
            seller_id,
            buyer_id,
            product_id,
            settlement_month,
            order_amount,
            paid_amount,
            fee_amount,
            refund_amount,
            net_settlement_amount,
            settlement_status,
            paid_at
        )
        VALUES
        (
            '94000000-0000-0000-0000-000000000001',
            NULL,
            '91000000-0000-0000-0000-000000000001',
            '92000000-0000-0000-0000-000000000001',
            '50000000-0000-0000-0000-000000000001',
            '20000000-0000-0000-0000-000000000001',
            '40000000-0000-0000-0000-000000000001',
            '202607',
            10000,
            10000,
            1000,
            3000,
            6000,
            'READY',
            '2026-07-21T10:16:00'
        ),
        (
            '94000000-0000-0000-0000-000000000002',
            '95000000-0000-0000-0000-000000000002',
            '91000000-0000-0000-0000-000000000002',
            '92000000-0000-0000-0000-000000000002',
            '50000000-0000-0000-0000-000000000002',
            '20000000-0000-0000-0000-000000000002',
            '40000000-0000-0000-0000-000000000002',
            '202607',
            20000,
            20000,
            2000,
            0,
            18000,
            'COMPLETED',
            '2026-07-21T11:16:00'
        );

        INSERT INTO settlement.seller_settlements(
            seller_settlement_id,
            batch_id,
            settlement_month,
            seller_id,
            total_order_count,
            total_paid_amount,
            total_fee_amount,
            total_refund_amount,
            total_adjustment_amount,
            final_settlement_amount,
            status,
            fail_reason
        )
        VALUES
        (
            '95000000-0000-0000-0000-000000000001',
            '96000000-0000-0000-0000-000000000001',
            '202607',
            '50000000-0000-0000-0000-000000000001',
            1,
            10000,
            1000,
            3000,
            -500,
            5500,
            'COMPLETED',
            NULL
        ),
        (
            '95000000-0000-0000-0000-000000000002',
            '96000000-0000-0000-0000-000000000001',
            '202607',
            '50000000-0000-0000-0000-000000000002',
            2,
            20000,
            2000,
            0,
            0,
            18000,
            'COMPLETED',
            NULL
        );

        INSERT INTO settlement.settlement_batchs(
            batch_id,
            settlement_month,
            batch_type,
            status,
            started_at,
            ended_at,
            total_order_count,
            total_seller_count,
            total_settlement_amount,
            fail_reason,
            created_at
        )
        VALUES (
            '96000000-0000-0000-0000-000000000001',
            '202607',
            'SETTLEMENT_RUN',
            'COMPLETED',
            '2026-07-22T01:00:00',
            '2026-07-22T01:05:00',
            3,
            2,
            23500,
            NULL,
            '2026-07-22T00:59:00'
        );

        INSERT INTO settlement.settlement_adjustments(
            adjustment_id,
            seller_id,
            order_id,
            refund_id,
            settlement_month,
            adjustment_type,
            adjustment_amount,
            status,
            reason
        )
        VALUES
        (
            '97000000-0000-0000-0000-000000000001',
            '50000000-0000-0000-0000-000000000001',
            '92000000-0000-0000-0000-000000000001',
            '93000000-0000-0000-0000-000000000001',
            '202607',
            'POST_REFUND',
            -3000,
            'APPLIED',
            'private adjustment reason'
        ),
        (
            '97000000-0000-0000-0000-000000000002',
            '50000000-0000-0000-0000-000000000001',
            '92000000-0000-0000-0000-000000000001',
            '93000000-0000-0000-0000-000000000001',
            '202607',
            'POST_REFUND',
            -500,
            'APPLIED',
            'another private adjustment reason'
        );

        INSERT INTO settlement.daily_reconciliation_results(
            id,
            business_date,
            status,
            payment_count,
            total_payment_amount,
            refund_count,
            total_refund_amount,
            expected_settlement_amount,
            discrepancy_count,
            executed_at
        )
        VALUES (
            '98000000-0000-0000-0000-000000000001',
            '2026-07-21',
            'DISCREPANCY_FOUND',
            2,
            30000,
            1,
            3000,
            23500,
            3,
            '2026-07-22T02:00:00'
        );

        INSERT INTO settlement.daily_reconciliation_discrepancies(
            id,
            business_date,
            entity_type,
            reference_id,
            discrepancy_type,
            detail,
            created_at
        )
        VALUES
        (
            '99000000-0000-0000-0000-000000000001',
            '2026-07-21',
            'ORDER',
            '92000000-0000-0000-0000-000000000001',
            'AMOUNT_MISMATCH',
            'private discrepancy detail one',
            '2026-07-22T02:00:00'
        ),
        (
            '99000000-0000-0000-0000-000000000002',
            '2026-07-21',
            'ORDER',
            '92000000-0000-0000-0000-000000000002',
            'AMOUNT_MISMATCH',
            'private discrepancy detail two',
            '2026-07-22T02:00:00'
        ),
        (
            '99000000-0000-0000-0000-000000000003',
            '2026-07-21',
            'REFUND',
            '93000000-0000-0000-0000-000000000001',
            'MISSING_IN_SETTLEMENT',
            'private discrepancy detail three',
            '2026-07-22T02:00:00'
        );

        INSERT INTO orders.inbox_events(
            event_id,
            event_type,
            payload,
            status,
            error_message,
            created_at
        )
        VALUES
        (
            'private-order-event-id',
            'PaymentCompleted',
            '{"private":"payload"}',
            'PROCESSED',
            NULL,
            '2026-07-21T01:10:00Z'
        );
        INSERT INTO orders.outbox_events(topic, payload, status, created_at)
        VALUES ('order.completed', '{"private":"payload"}', 'PUBLISHED', '2026-07-21T01:20:00Z');
        INSERT INTO payment.outbox_events(
            id,
            aggregate_type,
            aggregate_id,
            topic,
            payload,
            status,
            created_at
        )
        VALUES
        (
            '9a000000-0000-0000-0000-000000000001',
            'PAYMENT',
            '91000000-0000-0000-0000-000000000001',
            'payment.completed',
            '{"private":"payload"}',
            'PUBLISHED',
            '2026-07-21T10:30:00'
        );
        INSERT INTO member.outbox_events(
            id,
            aggregate_type,
            aggregate_id,
            topic,
            payload,
            status,
            created_at
        )
        VALUES
        (
            '9b000000-0000-0000-0000-000000000001',
            'MEMBER',
            '20000000-0000-0000-0000-000000000001',
            'member.created',
            '{"private":"payload"}',
            'PENDING',
            '2026-07-21T10:40:00'
        );
        """);
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static String renderDdl(String password) throws IOException {
    ClassPathResource resource = new ClassPathResource("db/read-model/01-ai-read-model.sql");
    String ddl = resource.getContentAsString(StandardCharsets.UTF_8);
    assertThat(ddl)
        .contains("ALTER ROLE ai_query_app PASSWORD :'AI_QUERY_DB_PASSWORD'")
        .doesNotContain("SELECT *");
    return ddl.replace(":'AI_QUERY_DB_PASSWORD'", "'" + password + "'");
  }

  private static void applyReadModel(String ddl) throws SQLException {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute(ddl);
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private static Connection queryConnection() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("user", QUERY_ROLE);
    properties.setProperty("password", TEST_QUERY_PASSWORD);
    return DriverManager.getConnection(postgres.getJdbcUrl(), properties);
  }

  private static void executeAsQueryRole(String sql) throws SQLException {
    try (Connection connection = queryConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void executeAdmin(String sql) throws SQLException {
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<List<Object>> rows(Connection connection, String sql) throws SQLException {
    List<List<Object>> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      int columnCount = resultSet.getMetaData().getColumnCount();
      while (resultSet.next()) {
        List<Object> row = new ArrayList<>();
        for (int column = 1; column <= columnCount; column++) {
          row.add(resultSet.getObject(column));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  private static List<org.assertj.core.groups.Tuple> viewColumns(Connection connection)
      throws SQLException {
    String sql =
        """
        SELECT relation.relname, attribute.attname, attribute.attnum
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace schema
          ON schema.oid = relation.relnamespace
        JOIN pg_catalog.pg_attribute attribute
          ON attribute.attrelid = relation.oid
        WHERE schema.nspname = 'ai_read'
          AND relation.relkind = 'v'
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
        ORDER BY relation.relname, attribute.attnum
        """;
    List<org.assertj.core.groups.Tuple> columns = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        columns.add(tuple(resultSet.getString(1), resultSet.getString(2), resultSet.getInt(3)));
      }
    }
    return columns;
  }

  private static List<String> viewColumnNames(Connection connection) throws SQLException {
    String sql =
        """
        SELECT attribute.attname
        FROM pg_catalog.pg_class relation
        JOIN pg_catalog.pg_namespace schema
          ON schema.oid = relation.relnamespace
        JOIN pg_catalog.pg_attribute attribute
          ON attribute.attrelid = relation.oid
        WHERE schema.nspname = 'ai_read'
          AND relation.relkind = 'v'
          AND attribute.attnum > 0
          AND NOT attribute.attisdropped
        ORDER BY relation.relname, attribute.attnum
        """;
    List<String> columnNames = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        columnNames.add(resultSet.getString(1));
      }
    }
    return columnNames;
  }

  private static String scalar(Statement statement, String sql) throws SQLException {
    try (ResultSet resultSet = statement.executeQuery(sql)) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getString(1);
    }
  }

  private static Query orderQuery(
      List<Dimension> dimensions, PlanningDateRange period, TrendGrain grain) {
    return new Query(
        Dataset.ORDER,
        List.of(Measure.ORDER_COUNT),
        dimensions,
        TimeField.CREATED_AT,
        AggregateTimeScope.CREATED_PERIOD,
        period,
        grain,
        Comparison.NONE,
        Map.of(),
        Measure.ORDER_COUNT,
        SortDirection.DESC,
        20);
  }

  private static PlanningDateRange range(Instant startInclusive, Instant endExclusive) {
    ZoneId korea = ZoneId.of("Asia/Seoul");
    return new PlanningDateRange(startInclusive.atZone(korea), endExclusive.atZone(korea));
  }

  private static QueryHarness queryHarness() {
    return queryHarness(Instant.parse("2026-07-22T03:00:00Z"));
  }

  private static QueryHarness queryHarness(Instant currentTime) {
    ChatQueryDataSourceProperties properties = queryProperties(QUERY_ROLE, TEST_QUERY_PASSWORD);
    ChatDataSourceConfig config = new ChatDataSourceConfig();
    HikariDataSource dataSource = config.chatQueryDataSource(properties);
    NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    ReadModelStartupVerifier verifier = new ReadModelStartupVerifier(jdbcTemplate, properties);
    assertThat(verifier.verifyNow()).isEqualTo(Availability.AVAILABLE);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    JdbcAdminDataQueryAdapter adapter =
        new JdbcAdminDataQueryAdapter(
            jdbcTemplate, verifier, transactionManager, Clock.fixed(currentTime, ZoneOffset.UTC));
    JdbcAdminAnalyticsQueryAdapter analyticsAdapter =
        new JdbcAdminAnalyticsQueryAdapter(
            jdbcTemplate, verifier, transactionManager, Clock.fixed(currentTime, ZoneOffset.UTC));
    return new QueryHarness(dataSource, jdbcTemplate, adapter, analyticsAdapter);
  }

  private static ChatQueryDataSourceProperties queryProperties(String username, String password) {
    ChatQueryDataSourceProperties properties = new ChatQueryDataSourceProperties();
    properties.setEnabled(true);
    properties.setUrl(postgres.getJdbcUrl());
    properties.setUsername(username);
    properties.setPassword(password);
    return properties;
  }

  private static Availability verify(ChatQueryDataSourceProperties properties) {
    ChatDataSourceConfig config = new ChatDataSourceConfig();
    try (HikariDataSource dataSource = config.chatQueryDataSource(properties)) {
      NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
      ReadModelStartupVerifier verifier = new ReadModelStartupVerifier(jdbcTemplate, properties);
      return verifier.verifyNow();
    }
  }

  private record QueryHarness(
      HikariDataSource dataSource,
      NamedParameterJdbcTemplate jdbcTemplate,
      JdbcAdminDataQueryAdapter adapter,
      JdbcAdminAnalyticsQueryAdapter analyticsAdapter)
      implements AutoCloseable {

    @Override
    public void close() {
      dataSource.close();
    }
  }
}

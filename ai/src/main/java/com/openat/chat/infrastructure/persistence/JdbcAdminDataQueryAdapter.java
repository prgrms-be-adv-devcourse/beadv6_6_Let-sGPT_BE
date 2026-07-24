package com.openat.chat.infrastructure.persistence;

import com.openat.chat.application.dto.AdminDataQueryResult;
import com.openat.chat.application.port.AdminDataQueryPort;
import com.openat.chat.application.port.DataQueryCapabilityState;
import com.openat.chat.domain.query.AdminDataQueryPlan;
import com.openat.chat.domain.query.AdminDataQueryPlan.OrderLookup;
import com.openat.chat.domain.query.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcAdminDataQueryAdapter implements AdminDataQueryPort {

  private static final String EXPIRED_PAYMENT_PENDING_COUNT_SQL =
      """
      SELECT COUNT(*) AS order_count
      FROM ai_read.v_payment_pending_expirations
      WHERE payment_expires_at < :asOf
      """;

  private static final String ORDER_DETAIL_SQL =
      """
      SELECT
          order_number,
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
      FROM ai_read.lookup_order_detail(:orderNumber)
      """;

  private static final String ORDER_EVENTS_SQL =
      """
      SELECT
          event_sequence,
          occurred_at,
          previous_status,
          new_status,
          reason_code
      FROM ai_read.lookup_order_process_events(:orderNumber)
      ORDER BY event_sequence
      """;

  private static final String ORDER_SAGA_SQL =
      """
      SELECT
          current_step,
          compensating_since,
          saga_created_at,
          saga_updated_at
      FROM ai_read.lookup_order_current_saga(:orderNumber)
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final DataQueryCapabilityState capabilityState;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public JdbcAdminDataQueryAdapter(
      @Qualifier("chatQueryJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
      DataQueryCapabilityState capabilityState,
      @Qualifier("chatQueryTransactionManager") PlatformTransactionManager transactionManager,
      Clock clock) {
    this.jdbcTemplate = jdbcTemplate;
    this.capabilityState = capabilityState;
    this.clock = clock;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    transaction.setTimeout(3);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @Override
  public boolean isAvailable() {
    return capabilityState.isAvailable();
  }

  @Override
  public AdminDataQueryResult.Metric countExpiredPaymentPendingOrders() {
    requireAvailable();
    Instant asOf = clock.instant();
    Long count =
        transaction.execute(
            ignored ->
                jdbcTemplate.queryForObject(
                    EXPIRED_PAYMENT_PENDING_COUNT_SQL,
                    new MapSqlParameterSource("asOf", Timestamp.from(asOf)),
                    Long.class));
    if (count == null || count < 0) {
      throw new IllegalStateException("결제기한 경과 주문 지표가 올바르지 않아요.");
    }
    return new AdminDataQueryResult.Metric(count, asOf);
  }

  @Override
  public AdminDataQueryResult.OrderLookup lookupOrder(OrderLookup plan) {
    requireAvailable();
    AdminDataQueryResult.OrderLookup result =
        transaction.execute(ignored -> executeOrderLookup(plan));
    if (result == null) {
      throw new IllegalStateException("개별 주문 조회 결과를 만들지 못했어요.");
    }
    return result;
  }

  private AdminDataQueryResult.OrderLookup executeOrderLookup(OrderLookup plan) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource("orderNumber", plan.publicOrderNumber());
    Optional<AdminDataQueryResult.OrderSnapshot> snapshot =
        single(jdbcTemplate.query(ORDER_DETAIL_SQL, parameters, this::mapOrderSnapshot), "주문 스냅샷");
    if (snapshot.isPresent()
        && !plan.publicOrderNumber().equals(snapshot.orElseThrow().publicOrderNumber())) {
      throw new IllegalStateException("요청한 주문번호와 조회 결과가 일치하지 않아요.");
    }

    List<AdminDataQueryResult.OrderProcessEvent> fetchedEvents =
        plan.includeProcessEvents()
            ? jdbcTemplate.query(ORDER_EVENTS_SQL, parameters, this::mapOrderEvent)
            : List.of();
    BoundedOrderEvents boundedEvents = limitOrderEvents(fetchedEvents);

    Optional<AdminDataQueryResult.OrderSagaSnapshot> currentSaga =
        plan.includeCurrentSaga()
            ? single(jdbcTemplate.query(ORDER_SAGA_SQL, parameters, this::mapOrderSaga), "현재 사가")
                .filter(this::hasSaga)
            : Optional.empty();

    return new AdminDataQueryResult.OrderLookup(
        snapshot, boundedEvents.events(), currentSaga, boundedEvents.truncated(), clock.instant());
  }

  private AdminDataQueryResult.OrderSnapshot mapOrderSnapshot(ResultSet resultSet, int rowNumber)
      throws SQLException {
    AdminDataQueryResult.OrderSnapshot snapshot =
        new AdminDataQueryResult.OrderSnapshot(
            resultSet.getString("order_number"),
            resultSet.getString("product_name"),
            resultSet.getInt("quantity"),
            resultSet.getLong("unit_price"),
            resultSet.getLong("total_price"),
            resultSet.getString("status"),
            resultSet.getString("fail_code"),
            instant(resultSet, "payment_expires_at"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "paid_at"),
            instant(resultSet, "completed_at"),
            instant(resultSet, "cancelled_at"),
            instant(resultSet, "refunded_at"));
    validateSnapshot(snapshot);
    return snapshot;
  }

  private AdminDataQueryResult.OrderProcessEvent mapOrderEvent(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new AdminDataQueryResult.OrderProcessEvent(
        resultSet.getLong("event_sequence"),
        instant(resultSet, "occurred_at"),
        resultSet.getString("previous_status"),
        resultSet.getString("new_status"),
        resultSet.getString("reason_code"));
  }

  private AdminDataQueryResult.OrderSagaSnapshot mapOrderSaga(ResultSet resultSet, int rowNumber)
      throws SQLException {
    AdminDataQueryResult.OrderSagaSnapshot saga =
        new AdminDataQueryResult.OrderSagaSnapshot(
            resultSet.getString("current_step"),
            instant(resultSet, "compensating_since"),
            instant(resultSet, "saga_created_at"),
            instant(resultSet, "saga_updated_at"));
    if (hasSaga(saga) && (!safeToken(saga.currentStep()) || saga.createdAt() == null)) {
      throw new IllegalStateException("현재 사가 조회 결과 형식이 올바르지 않아요.");
    }
    return saga;
  }

  void validateSnapshot(AdminDataQueryResult.OrderSnapshot snapshot) {
    if (snapshot.publicOrderNumber() == null
        || snapshot.productName() == null
        || snapshot.productName().isBlank()
        || snapshot.productName().length() > 500
        || containsControlCharacter(snapshot.productName())
        || snapshot.quantity() < 1
        || snapshot.unitPrice() < 0
        || snapshot.totalPrice() < 0
        || !OrderStatus.supports(snapshot.status())
        || snapshot.createdAt() == null
        || snapshot.updatedAt() == null
        || (snapshot.failCode() != null && !safeToken(snapshot.failCode()))) {
      throw new IllegalStateException("주문 스냅샷 조회 결과 형식이 올바르지 않아요.");
    }
  }

  void validateEvents(List<AdminDataQueryResult.OrderProcessEvent> events) {
    if (events.size() > AdminDataQueryPlan.MAX_ORDER_EVENT_ROWS + 1) {
      throw new IllegalStateException("주문 처리 이벤트 행 수가 허용 범위를 벗어났어요.");
    }
    long previousSequence = 0;
    for (AdminDataQueryResult.OrderProcessEvent event : events) {
      if (event == null
          || event.sequence() <= previousSequence
          || event.occurredAt() == null
          || (event.previousStatus() != null && !OrderStatus.supports(event.previousStatus()))
          || !OrderStatus.supports(event.newStatus())
          || (event.reasonCode() != null && !safeToken(event.reasonCode()))) {
        throw new IllegalStateException("주문 처리 이벤트 결과 형식이 올바르지 않아요.");
      }
      previousSequence = event.sequence();
    }
  }

  BoundedOrderEvents limitOrderEvents(List<AdminDataQueryResult.OrderProcessEvent> fetchedEvents) {
    validateEvents(fetchedEvents);
    boolean truncated = fetchedEvents.size() > AdminDataQueryPlan.MAX_ORDER_EVENT_ROWS;
    List<AdminDataQueryResult.OrderProcessEvent> events =
        truncated
            ? List.copyOf(fetchedEvents.subList(0, AdminDataQueryPlan.MAX_ORDER_EVENT_ROWS))
            : List.copyOf(fetchedEvents);
    return new BoundedOrderEvents(events, truncated);
  }

  String orderDetailSql() {
    return ORDER_DETAIL_SQL;
  }

  String orderEventsSql() {
    return ORDER_EVENTS_SQL;
  }

  String orderSagaSql() {
    return ORDER_SAGA_SQL;
  }

  private boolean hasSaga(AdminDataQueryResult.OrderSagaSnapshot saga) {
    return saga.currentStep() != null
        || saga.compensatingSince() != null
        || saga.createdAt() != null
        || saga.updatedAt() != null;
  }

  private <T> Optional<T> single(List<T> rows, String label) {
    if (rows.size() > 1) {
      throw new IllegalStateException(label + " 조회 결과가 한 행을 초과했어요.");
    }
    return rows.stream().findFirst();
  }

  private boolean safeToken(String value) {
    if (value == null || value.isBlank() || value.length() > 80) {
      return false;
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!(character == '_'
          || (character >= 'A' && character <= 'Z')
          || (character >= '0' && character <= '9'))) {
        return false;
      }
    }
    return true;
  }

  private boolean containsControlCharacter(String value) {
    return value.chars().anyMatch(Character::isISOControl);
  }

  private Instant instant(ResultSet resultSet, String column) throws SQLException {
    Timestamp timestamp = resultSet.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private void requireAvailable() {
    if (!isAvailable()) {
      throw new IllegalStateException("AI 읽기 모델을 사용할 수 없어요.");
    }
  }

  record BoundedOrderEvents(
      List<AdminDataQueryResult.OrderProcessEvent> events, boolean truncated) {}
}

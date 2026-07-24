package com.openat.chat.infrastructure.persistence;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult;
import com.openat.chat.application.dto.AdminAnalyticsQueryResult.Row;
import com.openat.chat.application.dto.AdminAnalyticsQueryResult.Series;
import com.openat.chat.application.port.AdminAnalyticsQueryPort;
import com.openat.chat.application.port.DataQueryCapabilityState;
import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.PlanningDateRange;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsCatalog;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dimension;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.FilterField;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Measure;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Query;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.TimeField;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcAdminAnalyticsQueryAdapter implements AdminAnalyticsQueryPort {

  private static final int MINIMUM_PROTECTED_GROUP_SIZE = 5;
  private static final int MAX_PROTECTED_CANDIDATE_ROWS = 200;
  private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Z0-9_]{1,80}");
  private static final Pattern PUBLIC_ORDER_NUMBER = Pattern.compile("ORD-[A-Za-z0-9-]{1,26}");
  private static final Pattern PUBLIC_RESOURCE_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private final NamedParameterJdbcTemplate jdbcTemplate;
  private final DataQueryCapabilityState capabilityState;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public JdbcAdminAnalyticsQueryAdapter(
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
  public AdminAnalyticsQueryResult query(Query plan) {
    requireAvailable();
    Instant asOf = clock.instant();
    QueryBatch batch =
        transaction.execute(
            ignored -> {
              QueryBatch current = execute(plan, plan.period(), Series.CURRENT, asOf);
              if (plan.comparison() == AdminAnalyticsQueryPlan.Comparison.NONE) {
                return current;
              }
              QueryBatch previous = execute(plan, previous(plan.period()), Series.PREVIOUS, asOf);
              List<Row> combined = new ArrayList<>(current.rows());
              combined.addAll(previous.rows());
              return new QueryBatch(
                  combined,
                  current.suppressedRowCount() + previous.suppressedRowCount(),
                  current.truncated() || previous.truncated());
            });
    if (batch == null) {
      throw new IllegalStateException("분석 결과를 만들지 못했어요.");
    }
    return new AdminAnalyticsQueryResult(
        batch.rows(), batch.suppressedRowCount(), batch.truncated(), asOf);
  }

  CompiledQuery compile(Query plan, PlanningDateRange range, Instant asOf) {
    DatasetSqlSpec spec = spec(plan.dataset());
    String timeColumn =
        plan.timeScope() == AggregateTimeScope.CREATED_PERIOD
            ? required(spec.timeColumns(), plan.timeField(), "시간 기준")
            : null;

    List<String> dimensionExpressions =
        plan.dimensions().stream()
            .map(dimension -> dimensionExpression(spec, dimension, timeColumn))
            .toList();
    String bucketExpression = bucketExpression(timeColumn, plan.grain());
    List<String> groupExpressions = new ArrayList<>();
    if (bucketExpression != null) {
      groupExpressions.add(bucketExpression);
    }
    groupExpressions.addAll(dimensionExpressions);

    List<String> select = new ArrayList<>();
    select.add(
        bucketExpression == null
            ? "NULL::timestamptz AS bucket_start"
            : bucketExpression + " AS bucket_start");
    for (int index = 0; index < dimensionExpressions.size(); index++) {
      select.add(dimensionExpressions.get(index) + " AS dimension_" + index);
    }
    for (int index = 0; index < plan.measures().size(); index++) {
      select.add(
          required(spec.measures(), plan.measures().get(index), "지표") + " AS measure_" + index);
    }
    select.add(contributorExpression(plan, spec) + " AS contributor_count");

    int fetchLimit =
        AdminAnalyticsCatalog.requiresMinimumGroupSize(plan.dataset())
            ? MAX_PROTECTED_CANDIDATE_ROWS + 1
            : plan.limit() + 1;
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("fetchLimit", fetchLimit)
            .addValue("asOf", Timestamp.from(asOf));
    List<String> predicates = new ArrayList<>();
    if (range != null) {
      predicates.add(timeColumn + " >= :fromInclusive");
      predicates.add(timeColumn + " < :toExclusive");
      parameters
          .addValue("fromInclusive", Timestamp.from(range.startInclusive().toInstant()))
          .addValue("toExclusive", Timestamp.from(range.endExclusive().toInstant()));
    }
    compileFilters(plan.filters(), spec, predicates, parameters);

    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(String.join(", ", select))
            .append(" FROM ")
            .append(spec.view());
    if (!predicates.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", predicates));
    }
    if (!groupExpressions.isEmpty()) {
      sql.append(" GROUP BY ").append(String.join(", ", groupExpressions));
    }
    sql.append(orderBy(plan, bucketExpression != null, dimensionExpressions.size()));
    sql.append(" LIMIT :fetchLimit");
    return new CompiledQuery(sql.toString(), parameters);
  }

  private QueryBatch execute(Query plan, PlanningDateRange range, Series series, Instant asOf) {
    CompiledQuery query = compile(plan, range, asOf);
    List<Row> fetched =
        jdbcTemplate.query(
            query.sql(),
            query.parameters(),
            (resultSet, rowNumber) -> mapRow(resultSet, plan, series));
    validateRows(fetched, plan);
    if (AdminAnalyticsCatalog.requiresMinimumGroupSize(plan.dataset())) {
      if (fetched.size() > MAX_PROTECTED_CANDIDATE_ROWS) {
        throw new IllegalStateException("보호 집계 후보 행이 안전 검증 상한을 넘었어요.");
      }
      QueryBatch protectedRows = suppressProtectedRows(fetched, plan.dataset(), false);
      boolean truncated = protectedRows.rows().size() > plan.limit();
      List<Row> limited =
          truncated
              ? List.copyOf(protectedRows.rows().subList(0, plan.limit()))
              : protectedRows.rows();
      return new QueryBatch(limited, protectedRows.suppressedRowCount(), truncated);
    }

    boolean truncated = fetched.size() > plan.limit();
    List<Row> limited =
        truncated ? List.copyOf(fetched.subList(0, plan.limit())) : List.copyOf(fetched);
    return new QueryBatch(limited, 0, truncated);
  }

  private Row mapRow(ResultSet resultSet, Query plan, Series series) throws SQLException {
    Map<String, String> dimensions = new LinkedHashMap<>();
    for (int index = 0; index < plan.dimensions().size(); index++) {
      dimensions.put(
          plan.dimensions().get(index).name(), resultSet.getString("dimension_" + index));
    }

    Map<String, BigDecimal> measures = new LinkedHashMap<>();
    for (int index = 0; index < plan.measures().size(); index++) {
      BigDecimal value = resultSet.getBigDecimal("measure_" + index);
      measures.put(plan.measures().get(index).name(), value);
    }
    return new Row(
        series,
        instant(resultSet, "bucket_start"),
        dimensions,
        measures,
        resultSet.getLong("contributor_count"));
  }

  QueryBatch suppressProtectedRows(List<Row> rows, Dataset dataset, boolean truncated) {
    if (!AdminAnalyticsCatalog.requiresMinimumGroupSize(dataset) || rows.isEmpty()) {
      return new QueryBatch(rows, 0, truncated);
    }

    Map<Instant, List<Row>> buckets = new LinkedHashMap<>();
    rows.forEach(
        row -> buckets.computeIfAbsent(row.bucketStart(), ignored -> new ArrayList<>()).add(row));

    List<Row> visible = new ArrayList<>();
    int suppressedCount = 0;
    for (List<Row> bucketRows : buckets.values()) {
      List<Row> bucketVisible = new ArrayList<>();
      List<Row> bucketSuppressed = new ArrayList<>();
      for (Row row : bucketRows) {
        (row.contributorCount() < MINIMUM_PROTECTED_GROUP_SIZE ? bucketSuppressed : bucketVisible)
            .add(row);
      }
      if (bucketSuppressed.size() == 1 && !bucketVisible.isEmpty()) {
        Row secondary =
            bucketVisible.stream()
                .min(Comparator.comparingLong(Row::contributorCount))
                .orElseThrow();
        bucketVisible.remove(secondary);
        bucketSuppressed.add(secondary);
      }
      visible.addAll(bucketVisible);
      suppressedCount += bucketSuppressed.size();
    }
    return new QueryBatch(List.copyOf(visible), suppressedCount, truncated);
  }

  private String contributorExpression(Query plan, DatasetSqlSpec spec) {
    if (plan.dataset() == Dataset.MEMBER_REGISTRATION) {
      boolean newMembers = plan.measures().contains(Measure.NEW_MEMBER_COUNT);
      boolean withdrawnMembers = plan.measures().contains(Measure.WITHDRAWN_MEMBER_COUNT);
      if (newMembers && withdrawnMembers) {
        return "LEAST(COALESCE(SUM(new_member_count), 0), "
            + "COALESCE(SUM(withdrawn_member_count), 0))::bigint";
      }
      return newMembers
          ? "COALESCE(SUM(new_member_count), 0)::bigint"
          : "COALESCE(SUM(withdrawn_member_count), 0)::bigint";
    }
    if (plan.dataset() == Dataset.SELLER_SETTLEMENT) {
      return "COALESCE(MAX(seller_count), 0)::bigint";
    }
    return spec.contributorExpression();
  }

  private void validateRows(List<Row> rows, Query plan) {
    if (plan.grain() == TrendGrain.NONE && plan.dimensions().isEmpty() && rows.size() > 1) {
      throw new IllegalStateException("전체 분석 결과가 한 행을 초과했어요.");
    }
    for (Row row : rows) {
      if (row.contributorCount() < 0
          || row.measures().size() != plan.measures().size()
          || row.dimensions().size() != plan.dimensions().size()
          || ((plan.grain() == TrendGrain.NONE) != (row.bucketStart() == null))) {
        throw new IllegalStateException("분석 결과 형식이 올바르지 않아요.");
      }
      for (String value : row.dimensions().values()) {
        if (value == null
            || value.isBlank()
            || value.length() > 200
            || value.chars().anyMatch(Character::isISOControl)) {
          throw new IllegalStateException("분석 분류 값 형식이 올바르지 않아요.");
        }
      }
      for (Measure measure : plan.measures()) {
        if (row.measures().get(measure.name()) == null && measure != Measure.PRODUCT_PRICE) {
          throw new IllegalStateException("분석 지표 값 형식이 올바르지 않아요.");
        }
      }
    }
  }

  private void compileFilters(
      Map<FilterField, List<String>> filters,
      DatasetSqlSpec spec,
      List<String> predicates,
      MapSqlParameterSource parameters) {
    int index = 0;
    for (Map.Entry<FilterField, List<String>> entry : filters.entrySet()) {
      String column = required(spec.filters(), entry.getKey(), "필터");
      List<String> values =
          entry.getValue().stream().map(value -> filterValue(entry.getKey(), value)).toList();
      String parameter = "filter" + index++;
      predicates.add(column + " IN (:" + parameter + ")");
      parameters.addValue(parameter, values);
    }
  }

  private String filterValue(FilterField field, String value) {
    return switch (field) {
      case PRODUCT_NAME, CATEGORY_NAME -> value;
      case ORDER_NUMBER -> {
        if (!PUBLIC_ORDER_NUMBER.matcher(value).matches()) {
          throw new IllegalArgumentException("공개 주문번호 필터 형식이 올바르지 않아요.");
        }
        yield value;
      }
      case PRODUCT_ID, DROP_ID -> {
        if (!PUBLIC_RESOURCE_ID.matcher(value).matches()) {
          throw new IllegalArgumentException("공개 리소스 식별자 필터 형식이 올바르지 않아요.");
        }
        yield value.toLowerCase(Locale.ROOT);
      }
      default -> {
        String token = value.toUpperCase(Locale.ROOT);
        if (!SAFE_TOKEN.matcher(token).matches()) {
          throw new IllegalArgumentException("카탈로그 필터 값 형식이 올바르지 않아요.");
        }
        yield token;
      }
    };
  }

  private String dimensionExpression(DatasetSqlSpec spec, Dimension dimension, String timeColumn) {
    return switch (dimension) {
      case HOUR_OF_DAY -> {
        if (timeColumn == null) {
          throw new IllegalArgumentException("시간대 분류에는 기간 시간 기준이 필요해요.");
        }
        yield "to_char(" + timeColumn + " AT TIME ZONE 'Asia/Seoul', 'HH24')::text";
      }
      case DAY_OF_WEEK -> {
        if (timeColumn == null) {
          throw new IllegalArgumentException("요일 분류에는 기간 시간 기준이 필요해요.");
        }
        yield "extract(isodow FROM " + timeColumn + " AT TIME ZONE 'Asia/Seoul')::integer::text";
      }
      default -> required(spec.dimensions(), dimension, "분류 기준");
    };
  }

  private String bucketExpression(String timeColumn, TrendGrain grain) {
    if (grain == TrendGrain.NONE) {
      return null;
    }
    if (timeColumn == null) {
      throw new IllegalArgumentException("시간 추이에는 기간 시간 기준이 필요해요.");
    }
    return "date_trunc('"
        + grain.name().toLowerCase(Locale.ROOT)
        + "', "
        + timeColumn
        + " AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'";
  }

  private String orderBy(Query plan, boolean hasBucket, int dimensionCount) {
    List<String> terms = new ArrayList<>();
    if (hasBucket) {
      terms.add("bucket_start ASC");
    } else {
      int measureIndex = plan.measures().indexOf(plan.orderBy());
      terms.add(
          "measure_"
              + measureIndex
              + " "
              + (plan.sortDirection() == SortDirection.ASC ? "ASC" : "DESC")
              + " NULLS LAST");
    }
    for (int index = 0; index < dimensionCount; index++) {
      terms.add("dimension_" + index + " ASC NULLS LAST");
    }
    return " ORDER BY " + String.join(", ", terms);
  }

  private PlanningDateRange previous(PlanningDateRange current) {
    Duration duration = current.duration();
    return new PlanningDateRange(
        current.startInclusive().minus(duration), current.startInclusive());
  }

  private DatasetSqlSpec spec(Dataset dataset) {
    return switch (dataset) {
      case ORDER ->
          new DatasetSqlSpec(
              "ai_read.v_order_analytics",
              Map.of(
                  TimeField.CREATED_AT, "created_at",
                  TimeField.PAID_AT, "paid_at",
                  TimeField.COMPLETED_AT, "completed_at",
                  TimeField.CANCELLED_AT, "cancelled_at",
                  TimeField.REFUNDED_AT, "refunded_at"),
              Map.ofEntries(
                  metric(Measure.ORDER_COUNT, "COUNT(*)::numeric"),
                  metric(Measure.ORDER_QUANTITY, "MAX(quantity)::numeric"),
                  metric(Measure.ORDER_UNIT_PRICE, "MAX(unit_price)::numeric"),
                  metric(Measure.ORDER_TOTAL_PRICE, "MAX(total_price)::numeric"),
                  metric(
                      Measure.COMPLETED_QUANTITY,
                      "COALESCE(SUM(quantity) FILTER (WHERE completed_at IS NOT NULL), 0)::numeric"),
                  metric(
                      Measure.GROSS_COMPLETED_AMOUNT,
                      "COALESCE(SUM(total_price) FILTER (WHERE completed_at IS NOT NULL), 0)::numeric"),
                  metric(
                      Measure.AVERAGE_PAID_ORDER_AMOUNT,
                      "COALESCE(AVG(total_price) FILTER (WHERE paid_at IS NOT NULL), 0)::numeric"),
                  metric(
                      Measure.AVERAGE_ORDER_COMPLETION_SECONDS,
                      "COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - created_at))) "
                          + "FILTER (WHERE completed_at IS NOT NULL), 0)::numeric"),
                  metric(
                      Measure.P50_ORDER_COMPLETION_SECONDS,
                      "COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP "
                          + "(ORDER BY EXTRACT(EPOCH FROM (completed_at - created_at))) "
                          + "FILTER (WHERE completed_at IS NOT NULL), 0)::numeric"),
                  metric(
                      Measure.P95_ORDER_COMPLETION_SECONDS,
                      "COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP "
                          + "(ORDER BY EXTRACT(EPOCH FROM (completed_at - created_at))) "
                          + "FILTER (WHERE completed_at IS NOT NULL), 0)::numeric"),
                  metric(
                      Measure.PAYMENT_PENDING_ORDER_COUNT,
                      "COUNT(*) FILTER (WHERE status = 'PAYMENT_PENDING')::numeric"),
                  metric(
                      Measure.OLDEST_PAYMENT_PENDING_AGE_SECONDS,
                      "COALESCE(MAX(EXTRACT(EPOCH FROM "
                          + "(CAST(:asOf AS timestamptz) - created_at))) "
                          + "FILTER (WHERE status = 'PAYMENT_PENDING'), 0)::numeric"),
                  metric(
                      Measure.CANCEL_RATE,
                      rate("COUNT(*) FILTER (WHERE status = 'CANCELLED')", "COUNT(*)")),
                  metric(
                      Measure.FAILURE_RATE,
                      rate(
                          "COUNT(*) FILTER (WHERE status IN ('FAILED', 'REFUND_FAILED'))",
                          "COUNT(*)")),
                  metric(
                      Measure.REFUND_RATE,
                      rate(
                          "COUNT(*) FILTER (WHERE status IN ('REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED'))",
                          "COUNT(*) FILTER (WHERE completed_at IS NOT NULL)"))),
              Map.ofEntries(
                  dimension(Dimension.ORDER_NUMBER, "order_number::text"),
                  dimension(Dimension.STATUS, "status::text"),
                  dimension(Dimension.PRODUCT_NAME, "product_name::text"),
                  dimension(Dimension.CATEGORY_NAME, "category_name::text"),
                  dimension(Dimension.FAILURE_CODE, "COALESCE(fail_code::text, 'NONE')")),
              Map.ofEntries(
                  filter(FilterField.ORDER_NUMBER, "order_number"),
                  filter(FilterField.STATUS, "status"),
                  filter(FilterField.PRODUCT_NAME, "product_name"),
                  filter(FilterField.CATEGORY_NAME, "category_name"),
                  filter(FilterField.FAILURE_CODE, "fail_code")),
              "COUNT(*)::bigint");
      case PAYMENT ->
          new DatasetSqlSpec(
              "ai_read.v_payment_analytics",
              Map.of(
                  TimeField.CREATED_AT, "created_at",
                  TimeField.APPROVED_AT, "approved_at"),
              Map.ofEntries(
                  metric(Measure.PAYMENT_ATTEMPT_COUNT, "COUNT(*)::numeric"),
                  metric(
                      Measure.APPROVED_PAYMENT_COUNT,
                      "COUNT(*) FILTER (WHERE status IN ('APPROVED', 'PARTIALLY_REFUNDED', 'REFUNDED'))::numeric"),
                  metric(
                      Measure.APPROVED_AMOUNT,
                      "COALESCE(SUM(amount) FILTER (WHERE status IN ('APPROVED', 'PARTIALLY_REFUNDED', 'REFUNDED')), 0)::numeric"),
                  metric(
                      Measure.FAILED_PAYMENT_COUNT,
                      "COUNT(*) FILTER (WHERE status IN ('FAILED', 'CANCELED'))::numeric"),
                  metric(
                      Measure.PAYMENT_COMPLETION_RATE,
                      rate(
                          "COUNT(*) FILTER (WHERE status IN ('APPROVED', 'PARTIALLY_REFUNDED', 'REFUNDED'))",
                          "COUNT(*)")),
                  metric(
                      Measure.PAYMENT_REFUNDED_AMOUNT,
                      "COALESCE(SUM(refunded_amount), 0)::numeric"),
                  metric(
                      Measure.NET_PAYMENT_AMOUNT,
                      "COALESCE(SUM(amount - refunded_amount) FILTER "
                          + "(WHERE status IN ('APPROVED', 'PARTIALLY_REFUNDED', 'REFUNDED')), 0)::numeric")),
              Map.ofEntries(
                  dimension(Dimension.STATUS, "status::text"),
                  dimension(Dimension.PAYMENT_METHOD, "method::text"),
                  dimension(Dimension.PG_PROVIDER, "COALESCE(pg_provider::text, 'NONE')"),
                  dimension(Dimension.RECONCILIATION_STATUS, "pg_recon_status::text")),
              Map.ofEntries(
                  filter(FilterField.STATUS, "status"),
                  filter(FilterField.PAYMENT_METHOD, "method"),
                  filter(FilterField.PG_PROVIDER, "COALESCE(pg_provider::text, 'NONE')"),
                  filter(FilterField.RECONCILIATION_STATUS, "pg_recon_status")),
              "COUNT(*)::bigint");
      case REFUND ->
          new DatasetSqlSpec(
              "ai_read.v_refund_analytics",
              Map.of(
                  TimeField.CREATED_AT, "created_at",
                  TimeField.COMPLETED_AT, "completed_at"),
              Map.ofEntries(
                  metric(Measure.REFUND_REQUEST_COUNT, "COUNT(*)::numeric"),
                  metric(
                      Measure.REFUND_COMPLETED_COUNT,
                      "COUNT(*) FILTER (WHERE status = 'COMPLETE')::numeric"),
                  metric(Measure.REFUND_AMOUNT, "COALESCE(SUM(amount), 0)::numeric"),
                  metric(
                      Measure.REFUND_COMPLETION_RATE,
                      rate("COUNT(*) FILTER (WHERE status = 'COMPLETE')", "COUNT(*)"))),
              Map.ofEntries(
                  dimension(Dimension.STATUS, "status::text"),
                  dimension(Dimension.RECONCILIATION_STATUS, "pg_recon_status::text")),
              Map.ofEntries(
                  filter(FilterField.STATUS, "status"),
                  filter(FilterField.RECONCILIATION_STATUS, "pg_recon_status")),
              "COUNT(*)::bigint");
      case SETTLEMENT_ORDER ->
          new DatasetSqlSpec(
              "ai_read.v_settlement_order_analytics",
              Map.of(TimeField.PERIOD_START, "period_start"),
              Map.ofEntries(
                  metric(Measure.SETTLEMENT_ORDER_COUNT, "COUNT(*)::numeric"),
                  metric(Measure.SETTLEMENT_PAID_AMOUNT, "COALESCE(SUM(paid_amount), 0)::numeric"),
                  metric(Measure.SETTLEMENT_FEE_AMOUNT, "COALESCE(SUM(fee_amount), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_REFUND_AMOUNT, "COALESCE(SUM(refund_amount), 0)::numeric"),
                  metric(
                      Measure.NET_SETTLEMENT_AMOUNT,
                      "COALESCE(SUM(net_settlement_amount), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_FEE_RATE,
                      rate("COALESCE(SUM(fee_amount), 0)", "COALESCE(SUM(paid_amount), 0)"))),
              Map.ofEntries(
                  dimension(Dimension.STATUS, "settlement_status::text"),
                  dimension(Dimension.PRODUCT_NAME, "product_name::text"),
                  dimension(Dimension.CATEGORY_NAME, "category_name::text")),
              Map.ofEntries(
                  filter(FilterField.STATUS, "settlement_status"),
                  filter(FilterField.PRODUCT_NAME, "product_name"),
                  filter(FilterField.CATEGORY_NAME, "category_name")),
              "COUNT(*)::bigint");
      case SELLER_SETTLEMENT ->
          new DatasetSqlSpec(
              "ai_read.v_seller_settlement_analytics",
              Map.of(TimeField.PERIOD_START, "period_start"),
              Map.ofEntries(
                  metric(
                      Measure.SETTLEMENT_SELLER_COUNT, "COALESCE(SUM(seller_count), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_ORDER_COUNT,
                      "COALESCE(SUM(total_order_count), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_PAID_AMOUNT,
                      "COALESCE(SUM(total_paid_amount), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_FEE_AMOUNT, "COALESCE(SUM(total_fee_amount), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_REFUND_AMOUNT,
                      "COALESCE(SUM(total_refund_amount), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_ADJUSTMENT_AMOUNT,
                      "COALESCE(SUM(total_adjustment_amount), 0)::numeric"),
                  metric(
                      Measure.FINAL_SETTLEMENT_AMOUNT,
                      "COALESCE(SUM(final_settlement_amount), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_FEE_RATE,
                      rate(
                          "COALESCE(SUM(total_fee_amount), 0)",
                          "COALESCE(SUM(total_paid_amount), 0)"))),
              Map.of(Dimension.STATUS, "settlement_status::text"),
              Map.of(FilterField.STATUS, "settlement_status"),
              "COALESCE(SUM(seller_count), 0)::bigint");
      case SETTLEMENT_BATCH ->
          new DatasetSqlSpec(
              "ai_read.v_settlement_batch_analytics",
              Map.of(
                  TimeField.PERIOD_START, "period_start",
                  TimeField.CREATED_AT, "created_at",
                  TimeField.COMPLETED_AT, "ended_at"),
              Map.ofEntries(
                  metric(Measure.SETTLEMENT_BATCH_COUNT, "COUNT(*)::numeric"),
                  metric(
                      Measure.SETTLEMENT_BATCH_ORDER_COUNT,
                      "COALESCE(SUM(total_order_count), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_BATCH_SELLER_COUNT,
                      "COALESCE(SUM(total_seller_count), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_BATCH_AMOUNT,
                      "COALESCE(SUM(total_settlement_amount), 0)::numeric"),
                  metric(
                      Measure.AVERAGE_BATCH_DURATION_SECONDS,
                      "COALESCE(AVG(EXTRACT(EPOCH FROM (ended_at - started_at))) "
                          + "FILTER (WHERE started_at IS NOT NULL AND ended_at IS NOT NULL), 0)::numeric")),
              Map.ofEntries(
                  dimension(Dimension.STATUS, "status::text"),
                  dimension(Dimension.BATCH_TYPE, "batch_type::text")),
              Map.ofEntries(
                  filter(FilterField.STATUS, "status"),
                  filter(FilterField.BATCH_TYPE, "batch_type")),
              "COUNT(*)::bigint");
      case SETTLEMENT_ADJUSTMENT ->
          new DatasetSqlSpec(
              "ai_read.v_settlement_adjustment_analytics",
              Map.of(TimeField.PERIOD_START, "period_start"),
              Map.ofEntries(
                  metric(
                      Measure.SETTLEMENT_ADJUSTMENT_COUNT,
                      "COALESCE(SUM(adjustment_count), 0)::numeric"),
                  metric(
                      Measure.SETTLEMENT_ADJUSTMENT_AMOUNT,
                      "COALESCE(SUM(adjustment_amount), 0)::numeric")),
              Map.ofEntries(
                  dimension(Dimension.STATUS, "status::text"),
                  dimension(Dimension.ADJUSTMENT_TYPE, "adjustment_type::text")),
              Map.ofEntries(
                  filter(FilterField.STATUS, "status"),
                  filter(FilterField.ADJUSTMENT_TYPE, "adjustment_type")),
              "COALESCE(SUM(adjustment_count), 0)::bigint");
      case RECONCILIATION ->
          new DatasetSqlSpec(
              "ai_read.v_reconciliation_analytics",
              Map.of(TimeField.PERIOD_START, "period_start"),
              Map.ofEntries(
                  metric(Measure.RECONCILIATION_RUN_COUNT, "COUNT(*)::numeric"),
                  metric(
                      Measure.RECONCILIATION_PAYMENT_COUNT,
                      "COALESCE(SUM(payment_count), 0)::numeric"),
                  metric(
                      Measure.RECONCILIATION_PAYMENT_AMOUNT,
                      "COALESCE(SUM(total_payment_amount), 0)::numeric"),
                  metric(
                      Measure.RECONCILIATION_REFUND_COUNT,
                      "COALESCE(SUM(refund_count), 0)::numeric"),
                  metric(
                      Measure.RECONCILIATION_REFUND_AMOUNT,
                      "COALESCE(SUM(total_refund_amount), 0)::numeric"),
                  metric(
                      Measure.EXPECTED_SETTLEMENT_AMOUNT,
                      "COALESCE(SUM(expected_settlement_amount), 0)::numeric"),
                  metric(
                      Measure.DISCREPANCY_COUNT, "COALESCE(SUM(discrepancy_count), 0)::numeric")),
              Map.of(Dimension.STATUS, "status::text"),
              Map.of(FilterField.STATUS, "status"),
              "COUNT(*)::bigint");
      case RECONCILIATION_DISCREPANCY ->
          new DatasetSqlSpec(
              "ai_read.v_reconciliation_discrepancy_analytics",
              Map.of(TimeField.PERIOD_START, "period_start"),
              Map.of(Measure.DISCREPANCY_COUNT, "COALESCE(SUM(discrepancy_count), 0)::numeric"),
              Map.ofEntries(
                  dimension(Dimension.ENTITY_TYPE, "entity_type::text"),
                  dimension(Dimension.DISCREPANCY_TYPE, "discrepancy_type::text")),
              Map.ofEntries(
                  filter(FilterField.ENTITY_TYPE, "entity_type"),
                  filter(FilterField.DISCREPANCY_TYPE, "discrepancy_type")),
              "COALESCE(SUM(discrepancy_count), 0)::bigint");
      case MEMBER_CURRENT ->
          new DatasetSqlSpec(
              "ai_read.v_member_current_snapshot",
              Map.of(),
              Map.of(Measure.MEMBER_COUNT, "COALESCE(SUM(member_count), 0)::numeric"),
              Map.ofEntries(
                  dimension(Dimension.PLATFORM, "platform_type::text"),
                  dimension(Dimension.ROLE, "member_role::text")),
              Map.ofEntries(
                  filter(FilterField.PLATFORM, "platform_type"),
                  filter(FilterField.ROLE, "member_role")),
              "COALESCE(SUM(member_count), 0)::bigint");
      case MEMBER_REGISTRATION ->
          new DatasetSqlSpec(
              "ai_read.v_member_registration_analytics",
              Map.of(TimeField.PERIOD_START, "period_start"),
              Map.ofEntries(
                  metric(Measure.NEW_MEMBER_COUNT, "COALESCE(SUM(new_member_count), 0)::numeric"),
                  metric(
                      Measure.WITHDRAWN_MEMBER_COUNT,
                      "COALESCE(SUM(withdrawn_member_count), 0)::numeric")),
              Map.of(Dimension.PLATFORM, "platform_type::text"),
              Map.of(FilterField.PLATFORM, "platform_type"),
              "COALESCE(SUM(new_member_count + withdrawn_member_count), 0)::bigint");
      case PRODUCT ->
          new DatasetSqlSpec(
              "ai_read.v_product_analytics",
              Map.of(TimeField.CREATED_AT, "created_at"),
              Map.ofEntries(
                  metric(Measure.PRODUCT_COUNT, "COUNT(*)::numeric"),
                  metric(Measure.PRODUCT_PRICE, "MAX(price)::numeric"),
                  metric(
                      Measure.AVERAGE_PRODUCT_PRICE,
                      "COALESCE(AVG(price) FILTER (WHERE price IS NOT NULL), 0)::numeric"),
                  metric(Measure.WISHLIST_COUNT, "COALESCE(SUM(wishlist_count), 0)::numeric"),
                  metric(
                      Measure.INCOMPLETE_PRODUCT_COUNT,
                      "COUNT(*) FILTER (WHERE NOT price_configured "
                          + "OR NOT description_configured OR NOT image_configured)::numeric")),
              Map.ofEntries(
                  dimension(Dimension.PRODUCT_ID, "product_id::text"),
                  dimension(Dimension.PRODUCT_NAME, "product_name::text"),
                  dimension(Dimension.CATEGORY_NAME, "category_name::text"),
                  dimension(
                      Dimension.LIFECYCLE,
                      "CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE 'DELETED' END"),
                  dimension(
                      Dimension.CONTENT_COMPLETENESS,
                      "CASE WHEN price_configured AND description_configured AND image_configured "
                          + "THEN 'COMPLETE' ELSE 'INCOMPLETE' END")),
              Map.ofEntries(
                  filter(FilterField.PRODUCT_ID, "product_id::text"),
                  filter(FilterField.PRODUCT_NAME, "product_name"),
                  filter(FilterField.CATEGORY_NAME, "category_name"),
                  filter(
                      FilterField.LIFECYCLE,
                      "CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE 'DELETED' END")),
              "COUNT(*)::bigint");
      case DROP ->
          new DatasetSqlSpec(
              "ai_read.v_drop_analytics",
              Map.of(
                  TimeField.CREATED_AT, "created_at",
                  TimeField.OPEN_AT, "open_at",
                  TimeField.CLOSE_AT, "close_at"),
              Map.ofEntries(
                  metric(Measure.DROP_COUNT, "COUNT(*)::numeric"),
                  metric(Measure.DROP_PRICE, "COALESCE(MAX(drop_price), 0)::numeric"),
                  metric(Measure.INITIAL_STOCK, "COALESCE(SUM(total_quantity), 0)::numeric"),
                  metric(Measure.REMAINING_STOCK, "COALESCE(SUM(remaining_quantity), 0)::numeric"),
                  metric(
                      Measure.NET_RESERVED_STOCK,
                      "COALESCE(SUM(total_quantity - remaining_quantity), 0)::numeric"),
                  metric(Measure.DEDUCTED_STOCK, "COALESCE(SUM(deducted_quantity), 0)::numeric"),
                  metric(Measure.ROLLED_BACK_STOCK, "COALESCE(SUM(rollback_quantity), 0)::numeric"),
                  metric(
                      Measure.STOCK_RESERVATION_RATE,
                      rate(
                          "COALESCE(SUM(total_quantity - remaining_quantity), 0)",
                          "COALESCE(SUM(total_quantity), 0)")),
                  metric(
                      Measure.ROLLBACK_RATE,
                      rate(
                          "COALESCE(SUM(rollback_quantity), 0)",
                          "COALESCE(SUM(deducted_quantity), 0)")),
                  metric(
                      Measure.UNCONFIGURED_DROP_COUNT,
                      "COUNT(*) FILTER (WHERE NOT scheduled_close_configured "
                          + "OR NOT user_limit_configured)::numeric")),
              Map.ofEntries(
                  dimension(Dimension.DROP_ID, "drop_id::text"),
                  dimension(Dimension.STATUS, "current_status::text"),
                  dimension(Dimension.PRODUCT_NAME, "product_name::text"),
                  dimension(Dimension.CATEGORY_NAME, "category_name::text"),
                  dimension(Dimension.INVENTORY_STATE, "inventory_state::text")),
              Map.ofEntries(
                  filter(FilterField.DROP_ID, "drop_id::text"),
                  filter(FilterField.STATUS, "current_status"),
                  filter(FilterField.PRODUCT_NAME, "product_name"),
                  filter(FilterField.CATEGORY_NAME, "category_name"),
                  filter(FilterField.INVENTORY_STATE, "inventory_state")),
              "COUNT(*)::bigint");
      case EVENT_PIPELINE ->
          new DatasetSqlSpec(
              "ai_read.v_event_pipeline_analytics",
              Map.of(TimeField.EVENT_AT, "bucket_start"),
              Map.ofEntries(
                  metric(Measure.EVENT_COUNT, "COALESCE(SUM(event_count), 0)::numeric"),
                  metric(
                      Measure.PENDING_EVENT_COUNT,
                      "COALESCE(SUM(event_count) FILTER "
                          + "(WHERE status IN ('PENDING', 'READY')), 0)::numeric"),
                  metric(
                      Measure.FAILED_EVENT_COUNT,
                      "COALESCE(SUM(event_count) FILTER (WHERE status = 'FAILED'), 0)::numeric"),
                  metric(
                      Measure.OLDEST_PENDING_AGE_SECONDS,
                      "COALESCE(MAX(EXTRACT(EPOCH FROM "
                          + "(CAST(:asOf AS timestamptz) - oldest_event_at))) "
                          + "FILTER (WHERE status IN ('PENDING', 'READY')), 0)::numeric")),
              Map.ofEntries(
                  dimension(Dimension.STATUS, "status::text"),
                  dimension(Dimension.EVENT_SERVICE, "service_name::text"),
                  dimension(Dimension.EVENT_DIRECTION, "direction::text"),
                  dimension(Dimension.EVENT_TYPE, "event_type::text")),
              Map.ofEntries(
                  filter(FilterField.STATUS, "status"),
                  filter(FilterField.EVENT_SERVICE, "service_name"),
                  filter(FilterField.EVENT_DIRECTION, "direction"),
                  filter(FilterField.EVENT_TYPE, "event_type")),
              "COALESCE(SUM(event_count), 0)::bigint");
      case ORDER_SAGA ->
          new DatasetSqlSpec(
              "ai_read.v_order_saga_analytics",
              Map.of(TimeField.SAGA_UPDATED_AT, "saga_updated_at"),
              Map.ofEntries(
                  metric(Measure.SAGA_COUNT, "COUNT(*)::numeric"),
                  metric(
                      Measure.STALLED_SAGA_COUNT,
                      "COUNT(*) FILTER (WHERE compensating_since "
                          + "< CAST(:asOf AS timestamptz) - INTERVAL '10 minutes')::numeric")),
              Map.of(Dimension.SAGA_STEP, "current_step::text"),
              Map.of(FilterField.SAGA_STEP, "current_step"),
              "COUNT(*)::bigint");
    };
  }

  private static String rate(String numerator, String denominator) {
    return "COALESCE(ROUND(100.0 * ("
        + numerator
        + ") / NULLIF(("
        + denominator
        + "), 0), 2), 0)::numeric";
  }

  private static Map.Entry<Measure, String> metric(Measure measure, String expression) {
    return Map.entry(measure, expression);
  }

  private static Map.Entry<Dimension, String> dimension(Dimension dimension, String expression) {
    return Map.entry(dimension, expression);
  }

  private static Map.Entry<FilterField, String> filter(FilterField filter, String expression) {
    return Map.entry(filter, expression);
  }

  private <K> String required(Map<K, String> catalog, K key, String label) {
    String value = catalog.get(key);
    if (value == null) {
      throw new IllegalArgumentException("지원하지 않는 " + label + "이에요.");
    }
    return value;
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

  record CompiledQuery(String sql, MapSqlParameterSource parameters) {}

  private record DatasetSqlSpec(
      String view,
      Map<TimeField, String> timeColumns,
      Map<Measure, String> measures,
      Map<Dimension, String> dimensions,
      Map<FilterField, String> filters,
      String contributorExpression) {}

  record QueryBatch(List<Row> rows, int suppressedRowCount, boolean truncated) {}
}

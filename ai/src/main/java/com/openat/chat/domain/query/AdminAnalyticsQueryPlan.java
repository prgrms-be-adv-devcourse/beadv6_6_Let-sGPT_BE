package com.openat.chat.domain.query;

import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.PlanningDateRange;
import com.openat.chat.domain.planning.TrendGrain;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AdminAnalyticsQueryPlan {

  public static final int MAX_MEASURES = 4;
  public static final int MAX_DIMENSIONS = 3;
  public static final int MAX_FILTERS = 5;
  public static final int MAX_FILTER_VALUES = 10;
  public static final int MAX_ROWS = 20;

  private static final Duration MAX_PERIOD = Duration.ofDays(366);
  private static final int MAX_FILTER_VALUE_LENGTH = 200;

  private AdminAnalyticsQueryPlan() {}

  public record Query(
      Dataset dataset,
      List<Measure> measures,
      List<Dimension> dimensions,
      TimeField timeField,
      AggregateTimeScope timeScope,
      PlanningDateRange period,
      TrendGrain grain,
      Comparison comparison,
      Map<FilterField, List<String>> filters,
      Measure orderBy,
      SortDirection sortDirection,
      int limit) {

    public Query {
      Objects.requireNonNull(dataset, "dataset");
      measures = immutableDistinct(measures, "measures", MAX_MEASURES);
      dimensions = immutableDistinct(dimensions, "dimensions", MAX_DIMENSIONS);
      Objects.requireNonNull(timeField, "timeField");
      Objects.requireNonNull(timeScope, "timeScope");
      Objects.requireNonNull(grain, "grain");
      Objects.requireNonNull(comparison, "comparison");
      Objects.requireNonNull(orderBy, "orderBy");
      Objects.requireNonNull(sortDirection, "sortDirection");
      filters = immutableFilters(filters);

      if (timeScope == AggregateTimeScope.CREATED_PERIOD) {
        Objects.requireNonNull(period, "period");
        if (timeField == TimeField.NONE) {
          throw new IllegalArgumentException("기간 분석에는 시간 기준이 필요해요.");
        }
        if (period.duration().compareTo(MAX_PERIOD) > 0) {
          throw new IllegalArgumentException("분석 기간은 최대 366일까지 지원해요.");
        }
      } else {
        if (period != null || grain != TrendGrain.NONE || timeField != TimeField.NONE) {
          throw new IllegalArgumentException("현재 스냅샷에는 기간이나 추이 기준을 적용할 수 없어요.");
        }
        if (comparison != Comparison.NONE) {
          throw new IllegalArgumentException("현재 스냅샷은 이전 기간과 비교할 수 없어요.");
        }
      }
      if (!measures.contains(orderBy)) {
        throw new IllegalArgumentException("정렬 기준 지표는 요청한 지표 중에서 선택해야 해요.");
      }
      if (limit < 1 || limit > MAX_ROWS) {
        throw new IllegalArgumentException("분석 결과 행 수는 1개 이상 20개 이하여야 해요.");
      }
      AdminAnalyticsCatalog.validate(dataset, measures, dimensions, timeField, filters.keySet());
    }
  }

  public static int clampLimit(int requested) {
    if (requested < 1) {
      return 10;
    }
    return Math.min(requested, MAX_ROWS);
  }

  private static <T> List<T> immutableDistinct(List<T> values, String name, int maximum) {
    Objects.requireNonNull(values, name);
    List<T> copied = List.copyOf(values);
    if (copied.isEmpty() && "measures".equals(name)) {
      throw new IllegalArgumentException("분석 지표가 하나 이상 필요해요.");
    }
    if (copied.size() > maximum || copied.stream().distinct().count() != copied.size()) {
      throw new IllegalArgumentException(name + " 조합이 허용 범위를 벗어났어요.");
    }
    if (copied.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(name + "에 빈 항목을 사용할 수 없어요.");
    }
    return copied;
  }

  private static Map<FilterField, List<String>> immutableFilters(
      Map<FilterField, List<String>> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    if (source.size() > MAX_FILTERS) {
      throw new IllegalArgumentException("필터는 최대 5개까지 조합할 수 있어요.");
    }

    Map<FilterField, List<String>> copied = new LinkedHashMap<>();
    source.forEach(
        (field, values) -> {
          Objects.requireNonNull(field, "filter field");
          if (values == null
              || values.isEmpty()
              || values.size() > MAX_FILTER_VALUES
              || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("필터 값 조합이 허용 범위를 벗어났어요.");
          }
          List<String> normalized =
              values.stream().map(AdminAnalyticsQueryPlan::normalizeFilterValue).toList();
          copied.put(field, normalized);
        });
    return Map.copyOf(copied);
  }

  private static String normalizeFilterValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("필터 값은 비어 있을 수 없어요.");
    }
    String normalized = value.strip();
    if (normalized.isEmpty()
        || normalized.length() > MAX_FILTER_VALUE_LENGTH
        || normalized.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("필터 값 형식이 올바르지 않아요.");
    }
    return normalized;
  }

  public enum Dataset {
    ORDER,
    PAYMENT,
    REFUND,
    SETTLEMENT_ORDER,
    SELLER_SETTLEMENT,
    SETTLEMENT_BATCH,
    SETTLEMENT_ADJUSTMENT,
    RECONCILIATION,
    RECONCILIATION_DISCREPANCY,
    MEMBER_CURRENT,
    MEMBER_REGISTRATION,
    PRODUCT,
    DROP,
    EVENT_PIPELINE,
    ORDER_SAGA
  }

  public enum Measure {
    ORDER_COUNT,
    ORDER_QUANTITY,
    ORDER_UNIT_PRICE,
    ORDER_TOTAL_PRICE,
    COMPLETED_QUANTITY,
    GROSS_COMPLETED_AMOUNT,
    AVERAGE_PAID_ORDER_AMOUNT,
    AVERAGE_ORDER_COMPLETION_SECONDS,
    P50_ORDER_COMPLETION_SECONDS,
    P95_ORDER_COMPLETION_SECONDS,
    PAYMENT_PENDING_ORDER_COUNT,
    OLDEST_PAYMENT_PENDING_AGE_SECONDS,
    CANCEL_RATE,
    FAILURE_RATE,
    REFUND_RATE,

    PAYMENT_ATTEMPT_COUNT,
    APPROVED_PAYMENT_COUNT,
    APPROVED_AMOUNT,
    FAILED_PAYMENT_COUNT,
    PAYMENT_COMPLETION_RATE,
    PAYMENT_REFUNDED_AMOUNT,
    NET_PAYMENT_AMOUNT,

    REFUND_REQUEST_COUNT,
    REFUND_COMPLETED_COUNT,
    REFUND_AMOUNT,
    REFUND_COMPLETION_RATE,

    SETTLEMENT_ORDER_COUNT,
    SETTLEMENT_PAID_AMOUNT,
    SETTLEMENT_FEE_AMOUNT,
    SETTLEMENT_REFUND_AMOUNT,
    NET_SETTLEMENT_AMOUNT,
    SETTLEMENT_FEE_RATE,
    SETTLEMENT_SELLER_COUNT,
    FINAL_SETTLEMENT_AMOUNT,
    SETTLEMENT_ADJUSTMENT_AMOUNT,
    SETTLEMENT_ADJUSTMENT_COUNT,
    SETTLEMENT_BATCH_COUNT,
    SETTLEMENT_BATCH_ORDER_COUNT,
    SETTLEMENT_BATCH_SELLER_COUNT,
    SETTLEMENT_BATCH_AMOUNT,
    AVERAGE_BATCH_DURATION_SECONDS,

    RECONCILIATION_RUN_COUNT,
    RECONCILIATION_PAYMENT_COUNT,
    RECONCILIATION_PAYMENT_AMOUNT,
    RECONCILIATION_REFUND_COUNT,
    RECONCILIATION_REFUND_AMOUNT,
    EXPECTED_SETTLEMENT_AMOUNT,
    DISCREPANCY_COUNT,

    MEMBER_COUNT,
    NEW_MEMBER_COUNT,
    WITHDRAWN_MEMBER_COUNT,

    PRODUCT_COUNT,
    PRODUCT_PRICE,
    AVERAGE_PRODUCT_PRICE,
    WISHLIST_COUNT,
    INCOMPLETE_PRODUCT_COUNT,

    DROP_COUNT,
    DROP_PRICE,
    INITIAL_STOCK,
    REMAINING_STOCK,
    NET_RESERVED_STOCK,
    DEDUCTED_STOCK,
    ROLLED_BACK_STOCK,
    STOCK_RESERVATION_RATE,
    ROLLBACK_RATE,
    UNCONFIGURED_DROP_COUNT,

    EVENT_COUNT,
    PENDING_EVENT_COUNT,
    FAILED_EVENT_COUNT,
    OLDEST_PENDING_AGE_SECONDS,

    SAGA_COUNT,
    STALLED_SAGA_COUNT
  }

  public enum Dimension {
    ORDER_NUMBER,
    PRODUCT_ID,
    DROP_ID,
    STATUS,
    PRODUCT_NAME,
    CATEGORY_NAME,
    FAILURE_CODE,
    HOUR_OF_DAY,
    DAY_OF_WEEK,
    PAYMENT_METHOD,
    PG_PROVIDER,
    RECONCILIATION_STATUS,
    PLATFORM,
    ROLE,
    LIFECYCLE,
    CONTENT_COMPLETENESS,
    INVENTORY_STATE,
    BATCH_TYPE,
    ADJUSTMENT_TYPE,
    ENTITY_TYPE,
    DISCREPANCY_TYPE,
    EVENT_SERVICE,
    EVENT_DIRECTION,
    EVENT_TYPE,
    SAGA_STEP
  }

  public enum TimeField {
    NONE,
    CREATED_AT,
    PAID_AT,
    COMPLETED_AT,
    CANCELLED_AT,
    REFUNDED_AT,
    APPROVED_AT,
    PERIOD_START,
    OPEN_AT,
    CLOSE_AT,
    EVENT_AT,
    SAGA_UPDATED_AT
  }

  public enum FilterField {
    ORDER_NUMBER,
    PRODUCT_ID,
    DROP_ID,
    STATUS,
    PRODUCT_NAME,
    CATEGORY_NAME,
    FAILURE_CODE,
    PAYMENT_METHOD,
    PG_PROVIDER,
    RECONCILIATION_STATUS,
    PLATFORM,
    ROLE,
    LIFECYCLE,
    INVENTORY_STATE,
    BATCH_TYPE,
    ADJUSTMENT_TYPE,
    ENTITY_TYPE,
    DISCREPANCY_TYPE,
    EVENT_SERVICE,
    EVENT_DIRECTION,
    EVENT_TYPE,
    SAGA_STEP
  }

  public enum Comparison {
    NONE,
    PREVIOUS_PERIOD
  }

  public enum SortDirection {
    ASC,
    DESC
  }
}

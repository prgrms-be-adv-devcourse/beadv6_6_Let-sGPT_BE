package com.openat.chat.application.service;

import com.openat.chat.application.port.AdminChatInferencePort.FilterSpec;
import com.openat.chat.application.port.AdminChatInferencePort.QuerySpec;
import com.openat.chat.application.service.AdminQueryPeriodResolver.ResolvedPeriod;
import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.TimeRangePreset;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsCatalog;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dimension;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.FilterField;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Measure;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Query;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.TimeField;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AdminAnalyticsPlanFactory {

  private final AdminQueryPeriodResolver periodResolver;

  public AdminAnalyticsPlanFactory(AdminQueryPeriodResolver periodResolver) {
    this.periodResolver = periodResolver;
  }

  public PreparedQuery create(QuerySpec request) {
    if (request == null || request.dataset() == null) {
      throw new IllegalArgumentException("내부 데이터셋이 필요해요.");
    }
    Dataset dataset = request.dataset();
    List<FieldFailure> failures = new ArrayList<>();
    validateProtectedTimeRange(dataset, request.timeRange());
    ResolvedPeriod period =
        periodResolver.resolve(
            request.timeRange(), request.customStart(), request.customEndExclusive());
    List<Measure> measures = measures(dataset, request.metrics(), failures);
    if (measures.isEmpty()) {
      throw new IllegalArgumentException("실행 가능한 분석 지표가 하나 이상 필요해요.");
    }
    List<Dimension> dimensions =
        protectedDimensions(dataset, dimensions(dataset, request.dimensions(), failures), failures);
    dimensions = withRequiredDimensions(dataset, measures, dimensions, failures);
    TimeField timeField = timeField(dataset, period, request.timeField(), failures);
    TrendGrain grain = grain(period, request.grain(), failures);
    Comparison comparison = comparison(period, request.comparison(), failures);
    Map<FilterField, List<String>> filters = filters(dataset, request.filters(), failures);
    filters = withProductLifecycleDefault(dataset, period, filters);
    Measure orderBy = orderBy(request.orderBy(), measures, failures);
    int limit = AdminAnalyticsQueryPlan.clampLimit(request.limit());
    if (limit != request.limit()) {
      failures.add(
          new FieldFailure("limit", Integer.toString(request.limit()), "결과 행 수를 1~20 범위로 보정"));
    }

    Query query =
        new Query(
            dataset,
            measures,
            dimensions,
            timeField,
            period.timeScope(),
            period.range(),
            grain,
            comparison,
            filters,
            orderBy,
            request.sortDirection() == null ? SortDirection.DESC : request.sortDirection(),
            limit);
    return new PreparedQuery(query, period.label(), List.copyOf(failures));
  }

  private List<Measure> measures(
      Dataset dataset, List<String> requested, List<FieldFailure> failures) {
    List<Measure> resolved = new ArrayList<>();
    for (String value : requested) {
      Measure measure = enumValue(value, Measure.class);
      if (!AdminAnalyticsCatalog.supportsMeasure(dataset, measure)) {
        failures.add(new FieldFailure("metrics", value, "선택 영역에서 지원하지 않는 지표"));
      } else if (resolved.contains(measure)) {
        failures.add(new FieldFailure("metrics", value, "중복 지표"));
      } else if (resolved.size() >= AdminAnalyticsQueryPlan.MAX_MEASURES) {
        failures.add(new FieldFailure("metrics", value, "지표 최대 4개 초과"));
      } else {
        resolved.add(measure);
      }
    }
    return List.copyOf(resolved);
  }

  private List<Dimension> dimensions(
      Dataset dataset, List<String> requested, List<FieldFailure> failures) {
    List<Dimension> resolved = new ArrayList<>();
    for (String value : requested) {
      Dimension dimension = enumValue(value, Dimension.class);
      if (!AdminAnalyticsCatalog.supportsDimension(dataset, dimension)) {
        failures.add(new FieldFailure("dimensions", value, "선택 영역에서 지원하지 않는 분류 기준"));
      } else if (resolved.contains(dimension)) {
        failures.add(new FieldFailure("dimensions", value, "중복 분류 기준"));
      } else if (resolved.size() >= AdminAnalyticsQueryPlan.MAX_DIMENSIONS) {
        failures.add(
            new FieldFailure(
                "dimensions",
                value,
                "분류 기준 최대 " + AdminAnalyticsQueryPlan.MAX_DIMENSIONS + "개 초과"));
      } else {
        resolved.add(dimension);
      }
    }
    return List.copyOf(resolved);
  }

  private List<Dimension> protectedDimensions(
      Dataset dataset, List<Dimension> dimensions, List<FieldFailure> failures) {
    if (!AdminAnalyticsCatalog.requiresMinimumGroupSize(dataset) || dimensions.size() <= 1) {
      return dimensions;
    }
    dimensions.stream()
        .skip(1)
        .forEach(
            dimension ->
                failures.add(
                    new FieldFailure(
                        "dimensions", dimension.name(), "보호 집계는 차분 재식별 방지를 위해 분류 기준을 하나만 허용")));
    return List.of(dimensions.getFirst());
  }

  private List<Dimension> withRequiredDimensions(
      Dataset dataset,
      List<Measure> measures,
      List<Dimension> dimensions,
      List<FieldFailure> failures) {
    List<Dimension> required = AdminAnalyticsCatalog.requiredDimensions(dataset, measures);
    if (dimensions.containsAll(required)) {
      return dimensions;
    }

    List<Dimension> normalized = new ArrayList<>(required);
    for (Dimension dimension : dimensions) {
      if (normalized.contains(dimension)) {
        continue;
      }
      if (normalized.size() >= AdminAnalyticsQueryPlan.MAX_DIMENSIONS) {
        failures.add(
            new FieldFailure("dimensions", dimension.name(), "개별 지표의 필수 공개 식별자 분류를 우선해 제외"));
        continue;
      }
      normalized.add(dimension);
    }
    return List.copyOf(normalized);
  }

  private void validateProtectedTimeRange(Dataset dataset, TimeRangePreset timeRange) {
    if (!AdminAnalyticsCatalog.requiresMinimumGroupSize(dataset)
        || dataset == Dataset.MEMBER_CURRENT) {
      return;
    }
    if (timeRange == null
        || !EnumSet.of(
                TimeRangePreset.TODAY,
                TimeRangePreset.YESTERDAY,
                TimeRangePreset.THIS_WEEK,
                TimeRangePreset.LAST_WEEK,
                TimeRangePreset.THIS_MONTH,
                TimeRangePreset.LAST_MONTH)
            .contains(timeRange)) {
      throw new IllegalArgumentException("보호 집계는 고정된 달력 기간만 조회할 수 있어요.");
    }
  }

  private TimeField timeField(
      Dataset dataset, ResolvedPeriod period, String requested, List<FieldFailure> failures) {
    if (period.timeScope() == AggregateTimeScope.CURRENT_SNAPSHOT) {
      if (!AdminAnalyticsCatalog.supportsTimeField(dataset, TimeField.NONE)) {
        throw new IllegalArgumentException("선택한 데이터셋은 현재 스냅샷을 지원하지 않아요.");
      }
      TimeField value = enumValue(requested, TimeField.class);
      if (value != null && value != TimeField.NONE) {
        failures.add(new FieldFailure("timeField", value.name(), "현재 스냅샷에서는 시간 기준을 NONE으로 보정"));
      }
      return TimeField.NONE;
    }

    TimeField fallback = defaultTimeField(dataset);
    TimeField value = enumValue(requested, TimeField.class);
    if (AdminAnalyticsCatalog.supportsTimeField(dataset, value) && value != TimeField.NONE) {
      return value;
    }
    failures.add(
        new FieldFailure("timeField", requested, "지원하지 않는 시간 기준이라 " + fallback.name() + " 사용"));
    return fallback;
  }

  private TrendGrain grain(
      ResolvedPeriod period, TrendGrain requested, List<FieldFailure> failures) {
    TrendGrain value = requested == null ? TrendGrain.NONE : requested;
    if (period.timeScope() == AggregateTimeScope.CURRENT_SNAPSHOT && value != TrendGrain.NONE) {
      failures.add(new FieldFailure("grain", value.name(), "현재 스냅샷에서는 추이 단위를 NONE으로 보정"));
      return TrendGrain.NONE;
    }
    return value;
  }

  private Comparison comparison(
      ResolvedPeriod period, Comparison requested, List<FieldFailure> failures) {
    Comparison value = requested == null ? Comparison.NONE : requested;
    if (period.timeScope() == AggregateTimeScope.CURRENT_SNAPSHOT && value != Comparison.NONE) {
      failures.add(new FieldFailure("comparison", value.name(), "현재 스냅샷에서는 기간 비교를 NONE으로 보정"));
      return Comparison.NONE;
    }
    return value;
  }

  private Map<FilterField, List<String>> filters(
      Dataset dataset, List<FilterSpec> requested, List<FieldFailure> failures) {
    Map<FilterField, List<String>> resolved = new LinkedHashMap<>();
    for (FilterSpec filter : requested) {
      FilterField field = filter == null ? null : enumValue(filter.field(), FilterField.class);
      if (!AdminAnalyticsCatalog.supportsFilter(dataset, field)) {
        failures.add(
            new FieldFailure(
                "filters", filter == null ? "null" : filter.field(), "선택 영역에서 지원하지 않는 필터"));
      } else if (resolved.containsKey(field)) {
        failures.add(new FieldFailure("filters", field.name(), "중복 필터"));
      } else if (resolved.size() >= AdminAnalyticsQueryPlan.MAX_FILTERS) {
        failures.add(new FieldFailure("filters", field.name(), "필터 최대 5개 초과"));
      } else {
        List<String> values = normalizeFilterValues(filter.values());
        if (values.isEmpty()) {
          failures.add(new FieldFailure("filters." + field.name(), "invalid", "필터 값 형식 오류"));
        } else {
          resolved.put(field, values);
        }
      }
    }
    return Map.copyOf(resolved);
  }

  private Map<FilterField, List<String>> withProductLifecycleDefault(
      Dataset dataset, ResolvedPeriod period, Map<FilterField, List<String>> filters) {
    if (dataset != Dataset.PRODUCT
        || period.timeScope() != AggregateTimeScope.CURRENT_SNAPSHOT
        || filters.containsKey(FilterField.LIFECYCLE)) {
      return filters;
    }
    Map<FilterField, List<String>> withDefault = new LinkedHashMap<>(filters);
    withDefault.put(FilterField.LIFECYCLE, List.of("ACTIVE"));
    return Map.copyOf(withDefault);
  }

  private Measure orderBy(String requested, List<Measure> measures, List<FieldFailure> failures) {
    Measure value = enumValue(requested, Measure.class);
    if (value != null && measures.contains(value)) {
      return value;
    }
    if (requested != null && !requested.isBlank()) {
      failures.add(new FieldFailure("orderBy", requested, "실행 지표에 없는 정렬 기준이라 첫 번째 지표 사용"));
    }
    return measures.getFirst();
  }

  private List<String> normalizeFilterValues(List<String> values) {
    if (values == null
        || values.isEmpty()
        || values.size() > AdminAnalyticsQueryPlan.MAX_FILTER_VALUES) {
      return List.of();
    }
    List<String> normalized = new ArrayList<>();
    for (String value : values) {
      if (value == null) {
        return List.of();
      }
      String candidate = value.strip();
      if (candidate.isEmpty()
          || candidate.length() > 200
          || candidate.chars().anyMatch(Character::isISOControl)
          || normalized.contains(candidate)) {
        return List.of();
      }
      normalized.add(candidate);
    }
    return List.copyOf(normalized);
  }

  private TimeField defaultTimeField(Dataset dataset) {
    return switch (dataset) {
      case ORDER, PAYMENT, REFUND, PRODUCT, DROP -> TimeField.CREATED_AT;
      case SETTLEMENT_ORDER,
              SELLER_SETTLEMENT,
              SETTLEMENT_BATCH,
              SETTLEMENT_ADJUSTMENT,
              RECONCILIATION,
              RECONCILIATION_DISCREPANCY,
              MEMBER_REGISTRATION ->
          TimeField.PERIOD_START;
      case MEMBER_CURRENT, ORDER_SAGA -> TimeField.NONE;
      case EVENT_PIPELINE -> TimeField.EVENT_AT;
    };
  }

  private <T extends Enum<T>> T enumValue(String value, Class<T> type) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  public record PreparedQuery(Query query, String periodLabel, List<FieldFailure> failures) {}

  public record FieldFailure(String field, String value, String reason) {

    public FieldFailure {
      value = value == null ? "null" : value;
    }
  }
}

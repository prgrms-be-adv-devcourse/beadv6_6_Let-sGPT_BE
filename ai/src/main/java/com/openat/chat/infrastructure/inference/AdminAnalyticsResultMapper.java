package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.dto.AdminAnalyticsQueryResult;
import com.openat.chat.application.service.AdminAnalyticsPlanFactory.PreparedQuery;
import com.openat.chat.domain.planning.PlanningDateRange;
import com.openat.chat.domain.planning.PlanningDateTimeValidator;
import com.openat.chat.domain.query.AdminAnalyticsCatalog;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.infrastructure.inference.tool.AdminAnalyticsFacts;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AdminAnalyticsResultMapper {

  public AdminAnalyticsFacts map(PreparedQuery prepared, AdminAnalyticsQueryResult result) {
    var query = prepared.query();
    List<AdminAnalyticsFacts.MetricDefinition> definitions =
        query.measures().stream()
            .map(
                metric -> {
                  AdminAnalyticsCatalog.MetricDefinition definition =
                      AdminAnalyticsCatalog.definition(metric);
                  return new AdminAnalyticsFacts.MetricDefinition(
                      metric.name(), definition.unit(), definition.description());
                })
            .toList();

    List<AdminAnalyticsFacts.Series> series = new ArrayList<>();
    series.add(series(AdminAnalyticsQueryResult.Series.CURRENT, result));
    if (query.comparison() == Comparison.PREVIOUS_PERIOD) {
      series.add(series(AdminAnalyticsQueryResult.Series.PREVIOUS, result));
    }

    List<AdminAnalyticsFacts.FieldFailure> failures =
        prepared.failures().stream()
            .map(
                failure ->
                    new AdminAnalyticsFacts.FieldFailure(
                        failure.field(), failure.value(), failure.reason()))
            .toList();
    return new AdminAnalyticsFacts(
        query.dataset().name(),
        definitions,
        period(prepared),
        query.grain().name(),
        List.copyOf(series),
        failures,
        new AdminAnalyticsFacts.Suppression(
            result.suppressedRowCount(),
            result.suppressedRowCount() == 0 ? "NONE" : "MINIMUM_GROUP_SIZE_5_AND_SECONDARY"),
        result.truncated(),
        kst(result.asOf()));
  }

  private AdminAnalyticsFacts.Series series(
      AdminAnalyticsQueryResult.Series series, AdminAnalyticsQueryResult result) {
    List<AdminAnalyticsFacts.Row> rows =
        result.rows().stream()
            .filter(row -> row.series() == series)
            .map(
                row ->
                    new AdminAnalyticsFacts.Row(
                        kst(row.bucketStart()), row.dimensions(), row.measures()))
            .toList();
    return new AdminAnalyticsFacts.Series(series.name(), rows);
  }

  private AdminAnalyticsFacts.Period period(PreparedQuery prepared) {
    PlanningDateRange currentRange = prepared.query().period();
    AdminAnalyticsFacts.Window current = window(currentRange);
    AdminAnalyticsFacts.Window previous =
        prepared.query().comparison() == Comparison.PREVIOUS_PERIOD
            ? window(previous(currentRange))
            : null;
    return new AdminAnalyticsFacts.Period(prepared.periodLabel(), "Asia/Seoul", current, previous);
  }

  private PlanningDateRange previous(PlanningDateRange current) {
    if (current == null) {
      return null;
    }
    Duration duration = current.duration();
    return new PlanningDateRange(
        current.startInclusive().minus(duration), current.startInclusive());
  }

  private AdminAnalyticsFacts.Window window(PlanningDateRange range) {
    if (range == null) {
      return null;
    }
    return new AdminAnalyticsFacts.Window(
        range.startInclusive().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        range.endExclusive().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
  }

  private String kst(Instant instant) {
    if (instant == null) {
      return null;
    }
    return ZonedDateTime.ofInstant(instant, PlanningDateTimeValidator.SERVER_TIME_ZONE)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }
}

package com.openat.chat.infrastructure.inference.tool;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record AdminAnalyticsFacts(
    String dataset,
    List<MetricDefinition> definitions,
    Period period,
    String grain,
    List<Series> series,
    List<FieldFailure> failures,
    Suppression suppression,
    boolean truncated,
    String asOf) {

  public record MetricDefinition(String metric, String unit, String definition) {}

  public record Period(String label, String timeZone, Window current, Window previous) {}

  public record Window(String startInclusive, String endExclusive) {}

  public record Series(String name, List<Row> rows) {}

  public record Row(
      String bucketStart, Map<String, String> dimensions, Map<String, BigDecimal> measures) {}

  public record FieldFailure(String field, String value, String reason) {}

  public record Suppression(int rowCount, String rule) {}
}

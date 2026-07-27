package com.openat.chat.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AdminAnalyticsQueryResult(
    List<Row> rows, int suppressedRowCount, boolean truncated, Instant asOf) {

  public AdminAnalyticsQueryResult {
    rows = List.copyOf(rows);
  }

  public record Row(
      Series series,
      Instant bucketStart,
      Map<String, String> dimensions,
      Map<String, BigDecimal> measures,
      long contributorCount) {

    public Row {
      dimensions = Map.copyOf(dimensions);
      measures = Collections.unmodifiableMap(new LinkedHashMap<>(measures));
    }
  }

  public enum Series {
    CURRENT,
    PREVIOUS
  }
}

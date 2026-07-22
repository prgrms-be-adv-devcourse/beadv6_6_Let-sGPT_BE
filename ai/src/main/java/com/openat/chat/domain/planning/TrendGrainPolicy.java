package com.openat.chat.domain.planning;

import java.time.Duration;
import java.util.Objects;

public final class TrendGrainPolicy {

  private static final Duration DEFAULT_DAY_UPPER_BOUND = Duration.ofDays(31);
  private static final Duration DEFAULT_WEEK_UPPER_BOUND = Duration.ofDays(180);

  private final Duration dayUpperBound;
  private final Duration weekUpperBound;

  public TrendGrainPolicy() {
    this(DEFAULT_DAY_UPPER_BOUND, DEFAULT_WEEK_UPPER_BOUND);
  }

  public TrendGrainPolicy(Duration dayUpperBound, Duration weekUpperBound) {
    this.dayUpperBound = requirePositive(dayUpperBound, "dayUpperBound");
    this.weekUpperBound = requirePositive(weekUpperBound, "weekUpperBound");
    if (dayUpperBound.compareTo(weekUpperBound) >= 0) {
      throw new IllegalArgumentException("DAY 자동 선택 상한은 WEEK 자동 선택 상한보다 작아야 해요.");
    }
  }

  public TrendGrain resolve(
      boolean trendRequested, ExplicitTrendGrain explicitGrain, PlanningDateRange dateRange) {
    Objects.requireNonNull(explicitGrain, "explicitGrain");
    Objects.requireNonNull(dateRange, "dateRange");

    if (explicitGrain != ExplicitTrendGrain.UNSPECIFIED) {
      return TrendGrain.valueOf(explicitGrain.name());
    }
    if (!trendRequested) {
      return TrendGrain.NONE;
    }

    Duration period = dateRange.duration();
    if (period.compareTo(dayUpperBound) <= 0) {
      return TrendGrain.DAY;
    }
    if (period.compareTo(weekUpperBound) <= 0) {
      return TrendGrain.WEEK;
    }
    return TrendGrain.MONTH;
  }

  private static Duration requirePositive(Duration duration, String name) {
    Objects.requireNonNull(duration, name);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(name + "은 양수여야 해요.");
    }
    return duration;
  }
}

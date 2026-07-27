package com.openat.chat.domain.planning;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Objects;

public record PlanningDateRange(ZonedDateTime startInclusive, ZonedDateTime endExclusive) {

  public PlanningDateRange {
    Objects.requireNonNull(startInclusive, "startInclusive");
    Objects.requireNonNull(endExclusive, "endExclusive");

    if (!startInclusive.getZone().equals(PlanningDateTimeValidator.SERVER_TIME_ZONE)
        || !endExclusive.getZone().equals(PlanningDateTimeValidator.SERVER_TIME_ZONE)) {
      throw new IllegalArgumentException("구조화 날짜의 시간대는 Asia/Seoul이어야 해요.");
    }
    if (!startInclusive.isBefore(endExclusive)) {
      throw new IllegalArgumentException("조회 시작 시각은 종료 미포함 시각보다 빨라야 해요.");
    }
  }

  public Duration duration() {
    return Duration.between(startInclusive, endExclusive);
  }
}

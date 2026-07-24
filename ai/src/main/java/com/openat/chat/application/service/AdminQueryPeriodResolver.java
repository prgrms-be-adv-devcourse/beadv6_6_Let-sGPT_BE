package com.openat.chat.application.service;

import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.PlanningDateRange;
import com.openat.chat.domain.planning.PlanningDateTimeValidator;
import com.openat.chat.domain.planning.TimeRangePreset;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

@Component
public class AdminQueryPeriodResolver {

  private final Clock clock;

  public AdminQueryPeriodResolver(Clock clock) {
    this.clock = clock;
  }

  public ResolvedPeriod resolve(
      TimeRangePreset preset, String customStartInclusive, String customEndExclusive) {
    if (preset == null) {
      throw new IllegalArgumentException("조회 기간 카탈로그가 필요해요.");
    }
    if (preset == TimeRangePreset.CURRENT_SNAPSHOT) {
      return new ResolvedPeriod(AggregateTimeScope.CURRENT_SNAPSHOT, null, "현재 스냅샷");
    }

    ZonedDateTime now =
        ZonedDateTime.now(clock).withZoneSameInstant(PlanningDateTimeValidator.SERVER_TIME_ZONE);
    ZonedDateTime start;
    ZonedDateTime end;
    String label;
    switch (preset) {
      case TODAY -> {
        start = now.toLocalDate().atStartOfDay(PlanningDateTimeValidator.SERVER_TIME_ZONE);
        end = start.plusDays(1);
        label = "오늘";
      }
      case YESTERDAY -> {
        end = now.toLocalDate().atStartOfDay(PlanningDateTimeValidator.SERVER_TIME_ZONE);
        start = end.minusDays(1);
        label = "어제";
      }
      case THIS_WEEK -> {
        start =
            now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(PlanningDateTimeValidator.SERVER_TIME_ZONE);
        end = start.plusWeeks(1);
        label = "이번 주";
      }
      case LAST_WEEK -> {
        end =
            now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(PlanningDateTimeValidator.SERVER_TIME_ZONE);
        start = end.minusWeeks(1);
        label = "지난주";
      }
      case THIS_MONTH -> {
        start =
            now.toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(PlanningDateTimeValidator.SERVER_TIME_ZONE);
        end = start.plusMonths(1);
        label = "이번 달";
      }
      case LAST_MONTH -> {
        end =
            now.toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(PlanningDateTimeValidator.SERVER_TIME_ZONE);
        start = end.minusMonths(1);
        label = "지난달";
      }
      case RECENT_24_HOURS -> {
        end = now;
        start = end.minusHours(24);
        label = "최근 24시간";
      }
      case RECENT_7_DAYS -> {
        end = now;
        start = end.minusDays(7);
        label = "최근 7일";
      }
      case RECENT_30_DAYS -> {
        end = now;
        start = end.minusDays(30);
        label = "최근 30일";
      }
      case CUSTOM -> {
        start = parse(customStartInclusive, "시작");
        end = parse(customEndExclusive, "종료 미포함");
        label = "지정 기간";
      }
      default -> throw new IllegalArgumentException("지원하지 않는 조회 기간이에요.");
    }
    return new ResolvedPeriod(
        AggregateTimeScope.CREATED_PERIOD, new PlanningDateRange(start, end), label);
  }

  private ZonedDateTime parse(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CUSTOM 기간의 " + label + " 시각이 필요해요.");
    }
    return PlanningDateTimeValidator.parse(value);
  }

  public record ResolvedPeriod(
      AggregateTimeScope timeScope, PlanningDateRange range, String label) {}
}

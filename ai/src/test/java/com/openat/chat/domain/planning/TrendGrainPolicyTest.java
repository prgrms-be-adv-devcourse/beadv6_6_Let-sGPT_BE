package com.openat.chat.domain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TrendGrainPolicyTest {

  private final TrendGrainPolicy policy = new TrendGrainPolicy();

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("explicitGrains")
  @DisplayName("질문에 명시된 추이 단위는 추이 플래그와 자동 정책보다 우선한다")
  void resolve_explicitGrain_usesExplicitValue(
      ExplicitTrendGrain explicitGrain, TrendGrain expected) {
    TrendGrain result = policy.resolve(false, explicitGrain, rangeOfDays(365));

    assertThat(result).isEqualTo(expected);
  }

  @Test
  @DisplayName("명시 단위도 추이 요청도 없으면 추이 단위를 NONE으로 결정한다")
  void resolve_noTrendRequested_returnsNone() {
    TrendGrain result = policy.resolve(false, ExplicitTrendGrain.UNSPECIFIED, rangeOfDays(7));

    assertThat(result).isEqualTo(TrendGrain.NONE);
  }

  @ParameterizedTest(name = "{0}일 -> {1}")
  @MethodSource("automaticGrains")
  @DisplayName("명시 단위가 없는 추이는 기간 길이로 실제 단위를 결정한다")
  void resolve_automaticGrain_followsPeriodThresholds(long days, TrendGrain expected) {
    TrendGrain result = policy.resolve(true, ExplicitTrendGrain.UNSPECIFIED, rangeOfDays(days));

    assertThat(result).isEqualTo(expected);
  }

  private static Stream<Arguments> explicitGrains() {
    return Stream.of(
        Arguments.of(ExplicitTrendGrain.HOUR, TrendGrain.HOUR),
        Arguments.of(ExplicitTrendGrain.DAY, TrendGrain.DAY),
        Arguments.of(ExplicitTrendGrain.WEEK, TrendGrain.WEEK),
        Arguments.of(ExplicitTrendGrain.MONTH, TrendGrain.MONTH));
  }

  private static Stream<Arguments> automaticGrains() {
    return Stream.of(
        Arguments.of(31, TrendGrain.DAY),
        Arguments.of(32, TrendGrain.WEEK),
        Arguments.of(180, TrendGrain.WEEK),
        Arguments.of(181, TrendGrain.MONTH));
  }

  private static PlanningDateRange rangeOfDays(long days) {
    var start =
        LocalDateTime.of(2026, 1, 1, 0, 0).atZone(PlanningDateTimeValidator.SERVER_TIME_ZONE);
    return new PlanningDateRange(start, start.plusDays(days));
  }
}

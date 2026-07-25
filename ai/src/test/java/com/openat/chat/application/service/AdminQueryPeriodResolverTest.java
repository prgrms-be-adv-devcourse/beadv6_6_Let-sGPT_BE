package com.openat.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.chat.domain.planning.AggregateTimeScope;
import com.openat.chat.domain.planning.TimeRangePreset;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 집계 기간 카탈로그")
class AdminQueryPeriodResolverTest {

  private final AdminQueryPeriodResolver resolver =
      new AdminQueryPeriodResolver(
          Clock.fixed(Instant.parse("2026-07-23T15:30:00Z"), ZoneOffset.UTC));

  @Test
  @DisplayName("오늘은 KST 자정 기준 시작 포함·다음 날 종료 미포함으로 계산한다")
  void today_resolvesKstHalfOpenRange() {
    var resolved = resolver.resolve(TimeRangePreset.TODAY, "무시", "무시");

    assertThat(resolved.timeScope()).isEqualTo(AggregateTimeScope.CREATED_PERIOD);
    assertThat(resolved.range().startInclusive().toString())
        .isEqualTo("2026-07-24T00:00+09:00[Asia/Seoul]");
    assertThat(resolved.range().endExclusive().toString())
        .isEqualTo("2026-07-25T00:00+09:00[Asia/Seoul]");
  }

  @Test
  @DisplayName("CUSTOM이 아닌 프리셋은 모델이 잘못 채운 날짜 문자열을 무시한다")
  void preset_ignoresCustomFields() {
    assertThat(
            resolver
                .resolve(TimeRangePreset.LAST_MONTH, "not-a-date", "also-not-a-date")
                .range()
                .startInclusive()
                .getMonthValue())
        .isEqualTo(6);
  }

  @Test
  @DisplayName("CUSTOM은 엄격한 날짜 형식과 순서를 검증한다")
  void custom_validatesFormatAndOrder() {
    assertThatThrownBy(
            () -> resolver.resolve(TimeRangePreset.CUSTOM, "2026-07-07", "2026-07-01 00:00:00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    TimeRangePreset.CUSTOM, "2026-07-07 00:00:00", "2026-07-01 00:00:00"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("현재 스냅샷은 날짜 범위를 만들지 않는다")
  void currentSnapshot_hasNoRange() {
    var resolved = resolver.resolve(TimeRangePreset.CURRENT_SNAPSHOT, "", "");

    assertThat(resolved.timeScope()).isEqualTo(AggregateTimeScope.CURRENT_SNAPSHOT);
    assertThat(resolved.range()).isNull();
  }
}

package com.openat.chat.domain.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("구조화 날짜 검증")
class PlanningDateTimeValidatorTest {

  @Test
  @DisplayName("정확한 날짜 시각 형식을 Asia/Seoul로 해석한다")
  void parse_validDateTime() {
    assertThat(PlanningDateTimeValidator.parse("2026-07-01 00:00:00").getZone())
        .isEqualTo(ZoneId.of("Asia/Seoul"));
  }

  @Test
  @DisplayName("느슨한 형식과 존재하지 않는 날짜를 거부한다")
  void parse_invalidDateTime_rejects() {
    assertThatThrownBy(() -> PlanningDateTimeValidator.parse("2026-7-01 00:00:00"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PlanningDateTimeValidator.parse("2026-02-30 00:00:00"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

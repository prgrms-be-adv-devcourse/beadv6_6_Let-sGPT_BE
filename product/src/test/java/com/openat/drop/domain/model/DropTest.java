package com.openat.drop.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("드롭 도메인")
class DropTest {

  @Test
  @DisplayName("드롭을 예약하면 초기 상태는 SCHEDULED다")
  void schedule_whenScheduled_hasScheduledStatus() {
    // when
    Drop drop =
        Drop.schedule()
            .product(null)
            .dropPrice(10_000L)
            .totalQuantity(100)
            .limitPerUser(2)
            .openAt(Instant.parse("2026-07-01T00:00:00Z"))
            .closeAt(null)
            .build();

    // then
    assertThat(drop.getStatus()).isEqualTo(DropStatus.SCHEDULED);
  }
}

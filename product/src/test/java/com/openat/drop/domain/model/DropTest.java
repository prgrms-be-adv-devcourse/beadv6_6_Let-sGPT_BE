package com.openat.drop.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("드롭 도메인")
class DropTest {

  @Test
  @DisplayName("드롭을 예약하면 초기 상태는 REGISTERED다")
  void schedule_whenScheduled_hasRegisteredStatus() {
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
    assertThat(drop.getStatus()).isEqualTo(DropStatus.REGISTERED);
  }

  @Test
  @DisplayName("드롭을 종료하면 상태가 CLOSE가 된다")
  void close_whenClosed_hasCloseStatus() {
    // given
    Drop drop =
        Drop.schedule()
            .product(null)
            .dropPrice(10_000L)
            .totalQuantity(100)
            .openAt(Instant.parse("2026-07-01T00:00:00Z"))
            .build();

    // when
    drop.close();

    // then
    assertThat(drop.getStatus()).isEqualTo(DropStatus.CLOSE);
  }

  @Test
  @DisplayName("종료된 드롭은 잔여와 무관하게 CLOSE로 파생된다")
  void resolveStatus_closed_returnsClose() {
    // given
    Drop drop = liveDrop();
    drop.close();

    // when & then
    assertThat(drop.resolveStatus(Instant.now(), 10)).isEqualTo(DropStatus.CLOSE);
  }

  @Test
  @DisplayName("오픈 전이면 REGISTERED로 파생된다")
  void resolveStatus_beforeOpen_returnsRegistered() {
    // given
    Drop drop = dropOpeningAt(Instant.now().plusSeconds(3600), null);

    // when & then
    assertThat(drop.resolveStatus(Instant.now(), 100)).isEqualTo(DropStatus.REGISTERED);
  }

  @Test
  @DisplayName("오픈 구간이고 잔여가 있으면 OPEN으로 파생된다")
  void resolveStatus_liveWithStock_returnsOpen() {
    // given
    Drop drop = liveDrop();

    // when & then
    assertThat(drop.resolveStatus(Instant.now(), 5)).isEqualTo(DropStatus.OPEN);
  }

  @Test
  @DisplayName("오픈 구간이고 잔여가 없으면 SOLD_OUT으로 파생된다")
  void resolveStatus_liveWithoutStock_returnsSoldOut() {
    // given
    Drop drop = liveDrop();

    // when & then
    assertThat(drop.resolveStatus(Instant.now(), 0)).isEqualTo(DropStatus.SOLD_OUT);
  }

  @Test
  @DisplayName("종료 시각이 지났으면 상태가 REGISTERED여도 CLOSE로 파생된다")
  void resolveStatus_afterCloseAt_returnsClose() {
    // given
    Instant now = Instant.now();
    Drop drop = dropOpeningAt(now.minusSeconds(7200), now.minusSeconds(3600));

    // when & then
    assertThat(drop.resolveStatus(now, 10)).isEqualTo(DropStatus.CLOSE);
  }

  private Drop liveDrop() {
    return dropOpeningAt(Instant.now().minusSeconds(60), null);
  }

  private Drop dropOpeningAt(Instant openAt, Instant closeAt) {
    return Drop.schedule()
        .product(null)
        .dropPrice(10_000L)
        .totalQuantity(100)
        .openAt(openAt)
        .closeAt(closeAt)
        .build();
  }
}

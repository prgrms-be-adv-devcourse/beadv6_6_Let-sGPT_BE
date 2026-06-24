package com.openat.drop.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("재고 이력 도메인")
class StockHistoryTest {

  @Test
  @DisplayName("DEDUCT 이력은 수량을 음수 delta로 기록한다")
  void record_deduct_storesNegativeDelta() {
    // given
    int quantity = 5;

    // when
    StockHistory history =
        StockHistory.record()
            .drop(null)
            .orderId(UUID.randomUUID())
            .buyerId(UUID.randomUUID())
            .changeType(StockChangeType.DEDUCT)
            .quantity(quantity)
            .build();

    // then
    assertThat(history.getQuantityDelta()).isEqualTo(-quantity);
  }

  @Test
  @DisplayName("ROLLBACK 이력은 수량을 양수 delta로 기록한다")
  void record_rollback_storesPositiveDelta() {
    // given
    int quantity = 5;

    // when
    StockHistory history =
        StockHistory.record()
            .drop(null)
            .orderId(UUID.randomUUID())
            .buyerId(UUID.randomUUID())
            .changeType(StockChangeType.ROLLBACK)
            .quantity(quantity)
            .build();

    // then
    assertThat(history.getQuantityDelta()).isEqualTo(quantity);
  }
}

package com.openat.order.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KafkaStringConfigTest {

  @Test
  void should_route_dead_letter_events_to_partition_zero() {
    var partition = KafkaStringConfig.deadLetterPartition("payment.failed.events");

    assertThat(partition.topic()).isEqualTo("payment.failed.events.dlq");
    assertThat(partition.partition()).isZero();
  }
}

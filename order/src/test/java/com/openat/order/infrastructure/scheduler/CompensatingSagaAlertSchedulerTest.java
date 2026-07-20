package com.openat.order.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompensatingSagaAlertSchedulerTest {

  @Mock OrderSagaStateRepository orderSagaStateRepository;
  @InjectMocks CompensatingSagaAlertScheduler scheduler;

  @Test
  void should_query_only_compensations_older_than_ten_minutes() {
    when(orderSagaStateRepository.findCompensatingBefore(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of(compensatingSaga()));
    Instant before = Instant.now();

    scheduler.alertOverdueCompensations();

    Instant after = Instant.now();
    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(orderSagaStateRepository).findCompensatingBefore(cutoff.capture());
    assertThat(cutoff.getValue())
        .isBetween(
            before.minus(CompensatingSagaAlertScheduler.ALERT_THRESHOLD),
            after.minus(CompensatingSagaAlertScheduler.ALERT_THRESHOLD));
    assertThat(CompensatingSagaAlertScheduler.ALERT_THRESHOLD).isEqualTo(Duration.ofMinutes(10));
  }

  private OrderSagaState compensatingSaga() {
    OrderSagaState state =
        OrderSagaState.create()
            .orderId(UUID.randomUUID())
            .sagaId(UUID.randomUUID().toString())
            .build();
    state.enterCompensating();
    return state;
  }
}

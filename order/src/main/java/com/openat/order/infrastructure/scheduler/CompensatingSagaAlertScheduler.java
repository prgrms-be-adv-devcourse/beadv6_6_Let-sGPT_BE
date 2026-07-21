package com.openat.order.infrastructure.scheduler;

import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompensatingSagaAlertScheduler {

  static final Duration ALERT_THRESHOLD = Duration.ofMinutes(10);

  private final OrderSagaStateRepository orderSagaStateRepository;

  @Scheduled(fixedDelay = 60_000)
  public void alertOverdueCompensations() {
    Instant now = Instant.now();
    orderSagaStateRepository
        .findCompensatingBefore(now.minus(ALERT_THRESHOLD))
        .forEach(state -> alert(state, now));
  }

  private void alert(OrderSagaState state, Instant now) {
    long residenceSeconds = Duration.between(state.getCompensatingSince(), now).toSeconds();
    log.error(
        "Order compensation is overdue. orderId={}, sagaId={}, residenceSeconds={}",
        state.getOrderId(),
        state.getSagaId(),
        residenceSeconds);
  }
}

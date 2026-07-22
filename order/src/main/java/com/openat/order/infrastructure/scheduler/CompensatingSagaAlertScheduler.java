package com.openat.order.infrastructure.scheduler;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.service.OrderCompensationService;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
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
  static final Duration RETRY_GRACE_PERIOD = Duration.ofMinutes(2);

  private final OrderSagaStateRepository orderSagaStateRepository;
  private final OrderRepository orderRepository;
  private final OrderCompensationService orderCompensationService;

  @Scheduled(fixedDelay = 60_000)
  public void processCompensations() {
    Instant now = Instant.now();
    orderSagaStateRepository.findCompensating().forEach(state -> retryIfEligible(state, now));
    orderSagaStateRepository
        .findCompensatingBefore(now.minus(ALERT_THRESHOLD))
        .forEach(state -> alert(state, now));
  }

  private void retryIfEligible(OrderSagaState state, Instant now) {
    orderRepository
        .findById(state.getOrderId())
        .filter(this::isRestorable)
        .filter(order -> transitionedAt(order) != null)
        .filter(order -> !transitionedAt(order).plus(RETRY_GRACE_PERIOD).isAfter(now))
        .ifPresent(this::retry);
  }

  private boolean isRestorable(Order order) {
    return order.getStatus() == OrderStatus.REFUNDED
        || order.getStatus() == OrderStatus.CANCELLED;
  }

  private Instant transitionedAt(Order order) {
    return order.getStatus() == OrderStatus.REFUNDED
        ? order.getRefundedAt()
        : order.getCancelledAt();
  }

  private void retry(Order order) {
    try {
      orderCompensationService.retryStockRollback(order.getId());
    } catch (BusinessException exception) {
      log.debug(
          "Order stock restoration retry skipped. orderId={}, sagaId={}",
          order.getId(),
          order.getSagaId(),
          exception);
    } catch (RuntimeException exception) {
      log.error(
          "Order stock restoration retry failed. orderId={}, sagaId={}",
          order.getId(),
          order.getSagaId(),
          exception);
    }
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

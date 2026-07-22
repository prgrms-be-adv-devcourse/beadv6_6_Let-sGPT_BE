package com.openat.order.application.service;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderSagaStep;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderSagaRecorder {

  private final OrderSagaStateRepository orderSagaStateRepository;
  private final MeterRegistry meterRegistry;

  @Transactional
  public void recordOrderCreated(Order order) {
    String sagaId = order.getId().toString();
    order.assignSagaId(sagaId);
    orderSagaStateRepository.save(
        OrderSagaState.create().orderId(order.getId()).sagaId(sagaId).build());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordStockDecreased(UUID orderId) {
    advance(orderId, OrderSagaStep.STOCK_DECREASED);
  }

  @Transactional
  public void recordCompleted(UUID orderId) {
    advance(orderId, OrderSagaStep.COMPLETED);
  }

  @Transactional
  public void recordCompensating(UUID orderId) {
    int updated = orderSagaStateRepository.enterCompensatingUnlessCompleted(orderId, Instant.now());
    if (updated > 0) {
      meterRegistry.counter("order.saga.compensation").increment();
    }
  }

  @Transactional(readOnly = true)
  public boolean isCompensationCompleted(UUID orderId) {
    return orderSagaStateRepository
        .findByOrderId(orderId)
        .map(state -> state.getCurrentStep() == OrderSagaStep.COMPENSATION_COMPLETED)
        .orElse(false);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordCompensationCompleted(UUID orderId) {
    advance(orderId, OrderSagaStep.COMPENSATION_COMPLETED);
  }

  private void advance(UUID orderId, OrderSagaStep step) {
    orderSagaStateRepository
        .findByOrderId(orderId)
        .ifPresent(
            sagaState -> {
              if (step.ordinal() <= sagaState.getCurrentStep().ordinal()) {
                return;
              }
              sagaState.advanceTo(step);
            });
  }
}

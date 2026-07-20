package com.openat.order.application.service;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderSagaStep;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderSagaRecorder {

  private final OrderSagaStateRepository orderSagaStateRepository;

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
    orderSagaStateRepository.findByOrderId(orderId).ifPresent(OrderSagaState::enterCompensating);
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

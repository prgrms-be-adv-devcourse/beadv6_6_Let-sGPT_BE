package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderSagaStep;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OrderSagaStateRepositoryAdaptor implements OrderSagaStateRepository {

  private final OrderSagaStateJpaRepository orderSagaStateJpaRepository;

  @Override
  public OrderSagaState save(OrderSagaState orderSagaState) {
    return orderSagaStateJpaRepository.save(orderSagaState);
  }

  @Override
  public Optional<OrderSagaState> findByOrderId(UUID orderId) {
    return orderSagaStateJpaRepository.findByOrderId(orderId);
  }

  @Override
  public int enterCompensatingUnlessCompleted(UUID orderId, Instant now) {
    return orderSagaStateJpaRepository.enterCompensatingUnlessCompleted(
        orderId,
        now,
        OrderSagaStep.COMPENSATING,
        OrderSagaStep.COMPENSATION_COMPLETED);
  }

  @Override
  public List<OrderSagaState> findCompensatingBefore(Instant cutoff) {
    return orderSagaStateJpaRepository.findByCurrentStepAndCompensatingSinceBefore(
        OrderSagaStep.COMPENSATING, cutoff);
  }

  @Override
  public List<OrderSagaState> findCompensating() {
    return orderSagaStateJpaRepository.findByCurrentStep(OrderSagaStep.COMPENSATING);
  }
}

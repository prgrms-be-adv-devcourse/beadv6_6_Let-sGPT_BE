package com.openat.order.domain.repository;

import com.openat.order.domain.model.OrderSagaState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderSagaStateRepository {

  OrderSagaState save(OrderSagaState orderSagaState);

  Optional<OrderSagaState> findByOrderId(UUID orderId);

  int enterCompensatingUnlessCompleted(UUID orderId, Instant now);

  List<OrderSagaState> findCompensatingBefore(Instant cutoff);

  List<OrderSagaState> findCompensating();
}

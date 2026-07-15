package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OrderSagaState;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSagaStateJpaRepository extends JpaRepository<OrderSagaState, UUID> {

    Optional<OrderSagaState> findByOrderId(UUID orderId);
}

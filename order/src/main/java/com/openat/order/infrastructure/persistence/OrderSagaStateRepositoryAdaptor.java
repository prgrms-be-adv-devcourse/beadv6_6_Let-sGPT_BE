package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.repository.OrderSagaStateRepository;
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
}

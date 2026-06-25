package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OrderHistory;
import com.openat.order.domain.repository.OrderHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OrderHistoryRepositoryAdaptor implements OrderHistoryRepository {

    private final OrderHistoryJpaRepository orderHistoryJpaRepository;

    @Override
    public OrderHistory save(OrderHistory orderHistory) {
        return orderHistoryJpaRepository.save(orderHistory);
    }
}

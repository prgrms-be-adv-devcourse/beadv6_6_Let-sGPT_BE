package com.openat.order.domain.repository;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(UUID id);

    Optional<Order> findByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey);

    Page<Order> findByMemberId(UUID memberId, OrderStatus status, Pageable pageable);
}

package com.openat.order.domain.repository;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.model.PurchaseSignal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderRepository {

    Order saveAndFlush(Order order);

    Optional<Order> findById(UUID id);

    Optional<Order> findByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey);

    Page<Order> findByMemberId(UUID memberId, OrderStatus status, Pageable pageable);

    List<PurchaseSignal> findPurchaseSignals(
            UUID memberId, OrderStatus status, Pageable pageable);
}

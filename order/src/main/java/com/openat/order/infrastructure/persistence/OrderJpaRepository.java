package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderJpaRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey);

    Page<Order> findByMemberId(UUID memberId, Pageable pageable);

    Page<Order> findByMemberIdAndStatus(UUID memberId, OrderStatus status, Pageable pageable);
}

package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.model.PurchaseSignal;
import com.openat.order.domain.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryAdaptor implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order saveAndFlush(Order order) {
        return orderJpaRepository.saveAndFlush(order);
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public Optional<Order> findByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey) {
        return orderJpaRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
    }

    @Override
    public Page<Order> findByMemberId(UUID memberId, OrderStatus status, Pageable pageable) {
        if (status == null) {
            return orderJpaRepository.findByMemberId(memberId, pageable);
        }
        return orderJpaRepository.findByMemberIdAndStatus(memberId, status, pageable);
    }

    @Override
    public List<PurchaseSignal> findPurchaseSignals(
            UUID memberId, OrderStatus status, Pageable pageable) {
        return orderJpaRepository.findPurchaseSignals(memberId, status, pageable);
    }
}

package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OrderHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderHistoryJpaRepository extends JpaRepository<OrderHistory, UUID> {
}

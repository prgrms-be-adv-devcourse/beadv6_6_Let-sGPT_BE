package com.openat.order.application.service;

import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderHistory;
import com.openat.order.domain.repository.OrderHistoryRepository;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PendingOrderCreator {

    private final OrderRepository orderRepository;
    private final OrderHistoryRepository orderHistoryRepository;

    @Transactional
    public Order create(UUID memberId, CreateOrderCommand command, OrderSnapshotInfo snapshot, Instant now) {
        Order order = Order.create()
                .orderNumber(generateOrderNumber(now))
                .memberId(memberId)
                .dropId(snapshot.dropId())
                .productId(snapshot.productId())
                .sellerId(snapshot.sellerId())
                .productName(snapshot.productName())
                .quantity(command.quantity())
                .unitPrice(snapshot.unitPrice())
                .idempotencyKey(command.idempotencyKey())
                .now(now)
                .build();

        Order savedOrder = orderRepository.saveAndFlush(order);
        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(savedOrder.getId())
                        .previousStatus(null)
                        .newStatus(savedOrder.getStatus())
                        .reasonCode("ORDER_CREATED")
                        .reasonMessage("주문 생성")
                        .sourceEventKey("order-create-" + savedOrder.getId())
                        .build()
        );
        return savedOrder;
    }

    private String generateOrderNumber(Instant now) {
        String timestamp = now.toString().replace("-", "").replace(":", "").replace("T", "").replace(".", "");
        String compact = timestamp.substring(0, Math.min(15, timestamp.length()));
        return "ORD-" + compact + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}

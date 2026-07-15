package com.openat.order.application.service;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderHistory;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderHistoryRecorder {

    private final OrderHistoryRepository orderHistoryRepository;

    public void record(
            Order order,
            OrderStatus previousStatus,
            String reasonCode,
            String reasonMessage,
            String sourceEventKey
    ) {
        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(order.getId())
                        .previousStatus(previousStatus)
                        .newStatus(order.getStatus())
                        .reasonCode(reasonCode)
                        .reasonMessage(reasonMessage)
                        .sourceEventKey(sourceEventKey)
                        .build()
        );
    }
}

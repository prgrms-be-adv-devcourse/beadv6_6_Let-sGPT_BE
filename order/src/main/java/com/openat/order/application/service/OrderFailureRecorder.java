package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderHistory;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderFailureRecorder {

    private final OrderRepository orderRepository;
    private final OrderHistoryRepository orderHistoryRepository;

    @Transactional
    public void recordCreateFailure(UUID orderId, OrderFailCode failCode, String message, Instant failedAt) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
        OrderStatus previousStatus = order.getStatus();
        if (!order.fail(failCode, message, failedAt)) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }
        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(order.getId())
                        .previousStatus(previousStatus)
                        .newStatus(order.getStatus())
                        .reasonCode("ORDER_CREATE_FAILED")
                        .reasonMessage(message)
                        .sourceEventKey("order-fail-" + order.getId())
                        .build()
        );
    }
}

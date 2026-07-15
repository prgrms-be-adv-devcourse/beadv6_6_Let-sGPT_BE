package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class OrderCancellationService {

    private final OrderRepository orderRepository;
    private final OrderHistoryRecorder orderHistoryRecorder;
    private final ProductIntegrationPort productIntegrationPort;
    private final OrderSagaRecorder orderSagaRecorder;
    private final OrderCompensationFailureRecorder compensationFailureRecorder;

    @Transactional
    public OrderCancelInfo cancel(UUID memberId, UUID orderId) {
        Order order = getOwnedOrder(memberId, orderId);
        OrderStatus previousStatus = order.getStatus();
        Instant requestedAt = Instant.now();

        if (previousStatus == OrderStatus.PAYMENT_PENDING) {
            cancelPaymentPendingOrder(order, previousStatus, requestedAt);
        } else if (previousStatus == OrderStatus.COMPLETED) {
            requestRefund(order, previousStatus, requestedAt);
        } else {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }

        return OrderCancelInfo.from(order);
    }

    private void cancelPaymentPendingOrder(Order order, OrderStatus previousStatus, Instant requestedAt) {
        if (!order.cancelPending(requestedAt)) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }
        orderHistoryRecorder.record(
                order, previousStatus, "ORDER_CANCELLED", "결제 대기 주문 취소", "cancel-" + order.getId());
        orderSagaRecorder.recordCompensating(order.getId());
        restoreStockAfterCommit(order);
    }

    private void requestRefund(Order order, OrderStatus previousStatus, Instant requestedAt) {
        if (!order.requestRefund(requestedAt)) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }
        orderHistoryRecorder.record(
                order, previousStatus, "ORDER_CANCEL_REQUESTED", "취소 요청 등록", "cancel-" + order.getId());
    }

    private Order getOwnedOrder(UUID memberId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
        if (!order.isOwnedBy(memberId)) {
            throw new BusinessException(OrderErrorCode.NOT_OWNER);
        }
        return order;
    }

    private void restoreStockAfterCommit(Order order) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            restoreStock(order);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                restoreStock(order);
            }
        });
    }

    private void restoreStock(Order order) {
        try {
            productIntegrationPort.restoreStock(
                    order.getDropId(),
                    new StockRestoreCommand(order.getId(), order.getMemberId(), order.getQuantity()));
        } catch (ProductPortException exception) {
            compensationFailureRecorder.recordStockRollbackFailure(order.getId(), exception.getMessage());
            return;
        }
        orderSagaRecorder.recordCompensationCompleted(order.getId());
    }
}

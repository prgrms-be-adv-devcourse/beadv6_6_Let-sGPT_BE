package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.port.OrderCompletedEventPublishPort;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderHistory;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
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
public class OrderEventService {

    private static final String EVENT_IDEMPOTENCY_DELIMITER = "-";
    private static final int SOURCE_EVENT_KEY_MAX_LENGTH = 100;

    private final OrderRepository orderRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final OrderCompletedEventPublishPort orderCompletedEventPublishPort;

    @Transactional
    public void handlePaymentCompleted(PaymentCompletedCommand command) {
        Order order = getOrder(command.orderId());

        if (order.getStatus() == OrderStatus.COMPLETED) {
            return;
        }

        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            throw new BusinessException(
                    OrderErrorCode.INVALID_STATUS,
                    "결제 성공 이벤트 처리 가능한 상태가 아닙니다: orderId=%s, status=%s"
                            .formatted(command.orderId(), order.getStatus())
            );
        }

        validateAmount(order, command.amount());

        OrderStatus before = order.getStatus();
        if (!order.complete(command.paymentId(), Instant.now())) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }

        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(order.getId())
                        .previousStatus(before)
                        .newStatus(order.getStatus())
                        .reasonCode("ORDER_COMPLETED")
                        .reasonMessage("결제 성공 이벤트 처리")
                        .sourceEventKey(eventSourceKey("payment-complete", command.orderId(), command.paymentId()))
                        .build()
        );

        publishOrderCompletedAfterCommit(order);
    }

    @Transactional
    public void handlePaymentFailed(PaymentFailedCommand command) {
        Order order = getOrder(command.orderId());

        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            return;
        }

        OrderStatus before = order.getStatus();

        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(order.getId())
                        .previousStatus(before)
                        .newStatus(order.getStatus())
                        .reasonCode("PAYMENT_ATTEMPT_FAILED")
                        .reasonMessage(command.reason())
                        .sourceEventKey(eventSourceKey("payment-failed", command.orderId(), command.paymentId()))
                        .build()
        );
    }

    @Transactional
    public void handleRefundCompleted(RefundCompletedCommand command) {
        Order order = getOrder(command.orderId());

        if (order.getStatus() == OrderStatus.REFUNDED) {
            return;
        }

        if (order.getStatus() != OrderStatus.CANCEL_REQUESTED && order.getStatus() != OrderStatus.REFUND_PENDING) {
            throw new BusinessException(
                    OrderErrorCode.INVALID_STATUS,
                    "환불 완료 이벤트 처리 가능한 상태가 아닙니다: orderId=%s, status=%s"
                            .formatted(command.orderId(), order.getStatus())
            );
        }

        validateAmount(order, command.amount());

        OrderStatus before = order.getStatus();
        if (!order.refund(Instant.now())) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }

        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(order.getId())
                        .previousStatus(before)
                        .newStatus(order.getStatus())
                        .reasonCode("ORDER_REFUNDED")
                        .reasonMessage("환불 완료 이벤트 처리")
                        .sourceEventKey(eventSourceKey("refund-completed", command.orderId(), command.refundId()))
                        .build()
        );
    }

    @Transactional
    public void handleRefundFailed(RefundFailedCommand command) {
        Order order = getOrder(command.orderId());

        if (order.getStatus() == OrderStatus.REFUND_FAILED) {
            return;
        }

        if (order.getStatus() != OrderStatus.CANCEL_REQUESTED && order.getStatus() != OrderStatus.REFUND_PENDING) {
            throw new BusinessException(
                    OrderErrorCode.INVALID_STATUS,
                    "환불 실패 이벤트 처리 가능한 상태가 아닙니다: orderId=%s, status=%s"
                            .formatted(command.orderId(), order.getStatus())
            );
        }

        OrderStatus before = order.getStatus();
        if (!order.failRefund(command.reason())) {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }

        orderHistoryRepository.save(
                OrderHistory.record()
                        .orderId(order.getId())
                        .previousStatus(before)
                        .newStatus(order.getStatus())
                        .reasonCode("REFUND_FAILED")
                        .reasonMessage(command.reason())
                        .sourceEventKey(eventSourceKey("refund-failed", command.orderId(), command.refundId()))
                        .build()
        );
    }

    private void validateAmount(Order order, long eventAmount) {
        if (order.getTotalPrice() != eventAmount) {
            throw new BusinessException(
                    OrderErrorCode.INVALID_INPUT,
                    "주문 금액과 이벤트 금액이 일치하지 않습니다: orderId=%s, orderAmount=%d, eventAmount=%d"
                            .formatted(order.getId(), order.getTotalPrice(), eventAmount)
            );
        }
    }

    private String eventSourceKey(String type, UUID orderId, UUID correlationId) {
        String key = type + EVENT_IDEMPOTENCY_DELIMITER + (correlationId != null ? correlationId : orderId);
        if (key.length() <= SOURCE_EVENT_KEY_MAX_LENGTH) {
            return key;
        }
        return key.substring(0, SOURCE_EVENT_KEY_MAX_LENGTH);
    }

    private Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
    }

    private void publishOrderCompletedAfterCommit(Order order) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderCompletedEventPublishPort.publish(order);
            }
        });
    }
}

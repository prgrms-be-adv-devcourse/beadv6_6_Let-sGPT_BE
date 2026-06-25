package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.OrderInfo;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.ProductOrderSnapshot;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.port.OrderCompletedEventPublisher;
import com.openat.order.application.port.ProductOrderPort;
import com.openat.order.application.usecase.OrderCommandUseCase;
import com.openat.order.domain.error.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderHistory;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderCommandService implements OrderCommandUseCase {

    private final OrderRepository orderRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final ProductOrderPort productOrderPort;
    private final OrderCompletedEventPublisher orderCompletedEventPublisher;
    private final OrderNumberGenerator orderNumberGenerator;
    private final Clock clock;

    @Override
    @Transactional
    public OrderInfo create(CreateOrderCommand command) {
        return orderRepository
                .findByMemberIdAndIdempotencyKey(command.memberId(), command.idempotencyKey())
                .map(OrderInfo::from)
                .orElseGet(() -> createNewOrder(command));
    }

    private OrderInfo createNewOrder(CreateOrderCommand command) {
        ProductOrderSnapshot snapshot = productOrderPort.getOrderSnapshot(command.dropId());
        Instant now = clock.instant();
        Order order = Order.create()
                .orderNumber(orderNumberGenerator.generate(clock))
                .memberId(command.memberId())
                .dropId(command.dropId())
                .productId(snapshot.productId())
                .sellerId(snapshot.sellerId())
                .productName(snapshot.productName())
                .quantity(command.quantity())
                .unitPrice(snapshot.unitPrice())
                .idempotencyKey(command.idempotencyKey())
                .now(now)
                .build();
        order = orderRepository.save(order);
        record(order.getId(), null, order.getStatus(), "ORDER_CREATED", "주문 생성", command.idempotencyKey());

        try {
            productOrderPort.decreaseStock(
                    order.getDropId(), order.getId(), order.getQuantity(), command.idempotencyKey());
            record(order.getId(), order.getStatus(), order.getStatus(), "STOCK_DECREASED", "재고 차감 완료", null);
        } catch (BusinessException e) {
            order.fail(resolveStockFailCode(e), e.getMessage(), now);
            record(order.getId(), OrderStatus.PAYMENT_PENDING, order.getStatus(), e.getErrorCode().getCode(), e.getMessage(), null);
        } catch (RuntimeException e) {
            order.fail(OrderFailCode.SOLD_OUT, e.getMessage(), now);
            record(order.getId(), OrderStatus.PAYMENT_PENDING, order.getStatus(), "STOCK_DECREASE_FAILED", e.getMessage(), null);
        }

        return OrderInfo.from(order);
    }

    @Override
    @Transactional
    public OrderInfo cancel(UUID memberId, UUID orderId) {
        Order order = getOrder(orderId);
        verifyOwner(order, memberId);
        OrderStatus previousStatus = order.getStatus();
        Instant now = clock.instant();

        if (previousStatus == OrderStatus.PAYMENT_PENDING) {
            if (!order.cancelPending(now)) {
                throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
            }
            productOrderPort.restoreStock(order.getDropId(), order.getId(), order.getQuantity(), restoreKey(order));
            record(order.getId(), previousStatus, order.getStatus(), "ORDER_CANCELLED", "결제 대기 주문 취소", null);
            return OrderInfo.from(order);
        }
        if (previousStatus == OrderStatus.COMPLETED) {
            if (!order.requestRefund(now)) {
                throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
            }
            record(order.getId(), previousStatus, order.getStatus(), "REFUND_REQUESTED", "환불 요청 대기", null);
            return OrderInfo.from(order);
        }
        throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
    }

    @Override
    @Transactional
    public void completePayment(PaymentCompletedCommand command) {
        Order order = getOrder(command.orderId());
        if (order.getTotalPrice() != command.amount()) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS, "결제 금액이 주문 금액과 일치하지 않습니다.");
        }
        OrderStatus previousStatus = order.getStatus();
        if (!order.complete(command.paymentId(), command.occurredAt())) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
        }
        record(order.getId(), previousStatus, order.getStatus(), "PAYMENT_COMPLETED", "결제 완료", command.eventId());
        orderCompletedEventPublisher.publish(order);
    }

    @Override
    @Transactional
    public void failPayment(PaymentFailedCommand command) {
        Order order = getOrder(command.orderId());
        OrderStatus previousStatus = order.getStatus();
        if (!order.failPayment(command.reason(), command.occurredAt())) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
        }
        productOrderPort.restoreStock(order.getDropId(), order.getId(), order.getQuantity(), restoreKey(order));
        record(order.getId(), previousStatus, order.getStatus(), "PAYMENT_FAILED", command.reason(), command.eventId());
    }

    @Override
    @Transactional
    public void completeRefund(RefundCompletedCommand command) {
        Order order = getOrder(command.orderId());
        OrderStatus previousStatus = order.getStatus();
        if (!order.refund(command.occurredAt())) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
        }
        record(order.getId(), previousStatus, order.getStatus(), "REFUND_COMPLETED", "환불 완료", command.eventId());
    }

    @Override
    @Transactional
    public void failRefund(RefundFailedCommand command) {
        Order order = getOrder(command.orderId());
        OrderStatus previousStatus = order.getStatus();
        if (!order.failRefund(command.reason())) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_STATUS);
        }
        record(order.getId(), previousStatus, order.getStatus(), "REFUND_FAILED", command.reason(), command.eventId());
    }

    private Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private void verifyOwner(Order order, UUID memberId) {
        if (!order.isOwnedBy(memberId)) {
            throw new BusinessException(OrderErrorCode.ORDER_FORBIDDEN);
        }
    }

    private OrderFailCode resolveStockFailCode(BusinessException e) {
        return switch (e.getErrorCode().getCode()) {
            case "NOT_OPEN" -> OrderFailCode.NOT_OPEN;
            case "LIMIT_EXCEEDED" -> OrderFailCode.LIMIT_EXCEEDED;
            default -> OrderFailCode.SOLD_OUT;
        };
    }

    private String restoreKey(Order order) {
        return "restore-" + order.getId();
    }

    private void record(
            UUID orderId,
            OrderStatus previousStatus,
            OrderStatus newStatus,
            String reasonCode,
            String reasonMessage,
            String sourceEventKey) {
        orderHistoryRepository.save(OrderHistory.record()
                .orderId(orderId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .reasonCode(reasonCode)
                .reasonMessage(reasonMessage)
                .sourceEventKey(sourceEventKey)
                .build());
    }
}

package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.OrderDetailInfo;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.OrderSummaryInfo;
import com.openat.order.application.dto.PaymentValidationInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.application.usecase.OrderUseCase;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderHistoryRepository orderHistoryRepository;
    private final ProductIntegrationPort productIntegrationPort;
    private final PendingOrderCreator pendingOrderCreator;
    private final OrderFailureRecorder orderFailureRecorder;

    @Override
    public CreateOrderResult createOrder(UUID memberId, CreateOrderCommand command) {
        validateCreateCommand(command);

        Order existing = findExisting(memberId, command.idempotencyKey());
        if (existing != null) {
            validateIdempotentReplay(existing, command);
            return CreateOrderResult.from(existing, false);
        }

        Instant now = Instant.now();
        OrderSnapshotInfo snapshot = productIntegrationPort.fetchOrderSnapshot(command.dropId());
        PendingOrderCreation creation = createPendingOrder(memberId, command, snapshot, now);
        Order order = creation.order();
        if (!creation.created()) {
            validateIdempotentReplay(order, command);
            return CreateOrderResult.from(order, false);
        }

        try {
            productIntegrationPort.decreaseStock(
                    order.getDropId(),
                    new StockDecreaseCommand(order.getId(), memberId, order.getQuantity())
            );
        } catch (ProductPortException e) {
            orderFailureRecorder.recordCreateFailure(order.getId(), e.getFailCode(), e.getMessage(), now);
            throw new BusinessException(toOrderErrorCode(e.getFailCode()), e.getMessage(), e);
        }

        return CreateOrderResult.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDetailInfo getMyOrder(UUID memberId, UUID orderId) {
        Order order = getOwnedOrder(memberId, orderId);
        return OrderDetailInfo.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getMyOrders(UUID memberId, OrderStatus status, Pageable pageable) {
        return orderRepository.findByMemberId(memberId, status, pageable)
                .map(OrderSummaryInfo::from);
    }

    @Override
    @Transactional
    public OrderCancelInfo cancelOrder(UUID memberId, UUID orderId) {
        Order order = getOwnedOrder(memberId, orderId);
        OrderStatus before = order.getStatus();
        Instant now = Instant.now();

        if (before == OrderStatus.PAYMENT_PENDING) {
            if (!order.cancelPending(now)) {
                throw new BusinessException(OrderErrorCode.INVALID_STATUS);
            }
            recordHistory(order, before, "ORDER_CANCELLED", "결제 대기 주문 취소", "cancel-" + order.getId());
            restoreStockAfterCommit(order);
        } else if (before == OrderStatus.COMPLETED) {
            if (!order.requestRefund(now)) {
                throw new BusinessException(OrderErrorCode.INVALID_STATUS);
            }
            recordHistory(order, before, "ORDER_CANCEL_REQUESTED", "취소 요청 등록", "cancel-" + order.getId());
        } else {
            throw new BusinessException(OrderErrorCode.INVALID_STATUS);
        }

        return OrderCancelInfo.from(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentValidationInfo getPaymentValidationInfo(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
        return PaymentValidationInfo.from(order);
    }

    private PendingOrderCreation createPendingOrder(
            UUID memberId,
            CreateOrderCommand command,
            OrderSnapshotInfo snapshot,
            Instant now
    ) {
        try {
            return new PendingOrderCreation(pendingOrderCreator.create(memberId, command, snapshot, now), true);
        } catch (DataIntegrityViolationException e) {
            Order existing = findExisting(memberId, command.idempotencyKey());
            if (existing != null) {
                return new PendingOrderCreation(existing, false);
            }
            throw e;
        }
    }

    private Order findExisting(UUID memberId, String idempotencyKey) {
        return orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
                .orElse(null);
    }

    private void validateIdempotentReplay(Order existing, CreateOrderCommand command) {
        if (!existing.getDropId().equals(command.dropId())
                || existing.getQuantity() != command.quantity()
                || !existing.getProductName().equals(command.orderName().trim())) {
            throw new BusinessException(
                    OrderErrorCode.IDEMPOTENCY_CONFLICT,
                    "동일 멱등키의 기존 주문과 요청 본문이 다릅니다: orderId=%s".formatted(existing.getId())
            );
        }
    }

    private Order getOwnedOrder(UUID memberId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
        if (!order.isOwnedBy(memberId)) {
            throw new BusinessException(OrderErrorCode.NOT_OWNER);
        }
        return order;
    }

    private void restoreStock(Order order) {
        try {
            productIntegrationPort.restoreStock(
                    order.getDropId(),
                    new StockRestoreCommand(order.getId(), order.getMemberId(), order.getQuantity())
            );
        } catch (ProductPortException e) {
            throw new BusinessException(OrderErrorCode.PORT_ERROR, e.getMessage(), e);
        }
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

    private void recordHistory(Order order, OrderStatus previousStatus, String reasonCode, String reasonMessage, String sourceEventKey) {
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

    private void validateCreateCommand(CreateOrderCommand command) {
        if (command == null || command.dropId() == null || command.quantity() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_INPUT);
        }
        if (!StringUtils.hasText(command.idempotencyKey())) {
            throw new BusinessException(OrderErrorCode.INVALID_INPUT);
        }
        if (!StringUtils.hasText(command.orderName())) {
            throw new BusinessException(OrderErrorCode.INVALID_INPUT);
        }
    }

    private OrderErrorCode toOrderErrorCode(OrderFailCode failCode) {
        return switch (failCode) {
            case SOLD_OUT -> OrderErrorCode.SOLD_OUT;
            case DROP_NOT_OPEN -> OrderErrorCode.DROP_NOT_OPEN;
            case DROP_CLOSED -> OrderErrorCode.DROP_CLOSED;
            case LIMIT_EXCEEDED -> OrderErrorCode.LIMIT_EXCEEDED;
            default -> OrderErrorCode.PORT_ERROR;
        };
    }
}

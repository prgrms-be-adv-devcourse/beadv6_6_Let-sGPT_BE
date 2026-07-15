package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private final OrderRepository orderRepository;
    private final ProductIntegrationPort productIntegrationPort;
    private final PendingOrderCreator pendingOrderCreator;
    private final OrderFailureRecorder orderFailureRecorder;

    public CreateOrderResult create(UUID memberId, CreateOrderCommand command) {
        validateCommand(command);

        Order existingOrder = findByIdempotencyKey(memberId, command.idempotencyKey());
        if (existingOrder != null) {
            return replay(existingOrder, command);
        }

        Instant requestedAt = Instant.now();
        OrderSnapshotInfo snapshot = productIntegrationPort.fetchOrderSnapshot(command.dropId());
        PendingOrderCreation creation = createPendingOrder(memberId, command, snapshot, requestedAt);
        if (!creation.created()) {
            return replay(creation.order(), command);
        }

        decreaseStock(creation.order(), requestedAt);
        return CreateOrderResult.from(creation.order());
    }

    private PendingOrderCreation createPendingOrder(
            UUID memberId,
            CreateOrderCommand command,
            OrderSnapshotInfo snapshot,
            Instant requestedAt
    ) {
        try {
            Order order = pendingOrderCreator.create(memberId, command, snapshot, requestedAt);
            return PendingOrderCreation.created(order);
        } catch (DataIntegrityViolationException exception) {
            Order concurrentOrder = findByIdempotencyKey(memberId, command.idempotencyKey());
            if (concurrentOrder != null) {
                return PendingOrderCreation.replayed(concurrentOrder);
            }
            throw exception;
        }
    }

    private void decreaseStock(Order order, Instant requestedAt) {
        try {
            productIntegrationPort.decreaseStock(
                    order.getDropId(),
                    new StockDecreaseCommand(order.getId(), order.getMemberId(), order.getQuantity()));
        } catch (ProductPortException exception) {
            orderFailureRecorder.recordCreateFailure(
                    order.getId(), exception.getFailCode(), exception.getMessage(), requestedAt);
            throw new BusinessException(
                    toOrderErrorCode(exception.getFailCode()), exception.getMessage(), exception);
        }
    }

    private Order findByIdempotencyKey(UUID memberId, String idempotencyKey) {
        return orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey).orElse(null);
    }

    private CreateOrderResult replay(Order existingOrder, CreateOrderCommand command) {
        validateSameRequest(existingOrder, command);
        rejectFailedOrder(existingOrder);
        return CreateOrderResult.from(existingOrder, false);
    }

    private void validateSameRequest(Order existingOrder, CreateOrderCommand command) {
        boolean sameRequest = existingOrder.getDropId().equals(command.dropId())
                && existingOrder.getQuantity() == command.quantity()
                && existingOrder.getProductName().equals(command.orderName().trim());
        if (!sameRequest) {
            throw new BusinessException(
                    OrderErrorCode.IDEMPOTENCY_CONFLICT,
                    "동일 멱등키의 기존 주문과 요청 본문이 다릅니다: orderId=%s"
                            .formatted(existingOrder.getId()));
        }
    }

    private void rejectFailedOrder(Order order) {
        if (order.getStatus() != OrderStatus.FAILED) {
            return;
        }
        OrderErrorCode errorCode = order.getFailCode() == null
                ? OrderErrorCode.INVALID_STATUS
                : toOrderErrorCode(order.getFailCode());
        if (StringUtils.hasText(order.getFailMessage())) {
            throw new BusinessException(errorCode, order.getFailMessage());
        }
        throw new BusinessException(errorCode);
    }

    private void validateCommand(CreateOrderCommand command) {
        if (command == null
                || command.dropId() == null
                || command.quantity() <= 0
                || !StringUtils.hasText(command.idempotencyKey())
                || !StringUtils.hasText(command.orderName())) {
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

package com.openat.order.infrastructure.client;

import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.ProductPortDtos.OperationType;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class ProductIntegrationClient implements ProductIntegrationPort {

    private final ProductInternalApiClient productInternalApiClient;

    @Override
    public OrderSnapshotInfo fetchOrderSnapshot(UUID dropId) {
        try {
            OrderSnapshotResponse response = productInternalApiClient.fetchOrderSnapshot(dropId);
            return new OrderSnapshotInfo(
                    response.productId(),
                    response.sellerId(),
                    response.unitPrice()
            );
        } catch (RestClientException exception) {
            throw toProductPortException(OperationType.FETCH_ORDER_SNAPSHOT, exception);
        }
    }

    @Override
    public void decreaseStock(UUID dropId, StockDecreaseCommand command) {
        try {
            productInternalApiClient.decreaseStock(
                    dropId,
                    new StockChangeRequest(command.orderId(), command.buyerId(), command.quantity())
            );
        } catch (RestClientException exception) {
            throw toProductPortException(OperationType.DECREASE_STOCK, exception);
        }
    }

    @Override
    public void restoreStock(UUID dropId, StockRestoreCommand command) {
        try {
            productInternalApiClient.restoreStock(
                    dropId,
                    new StockChangeRequest(command.orderId(), command.buyerId(), command.quantity())
            );
        } catch (RestClientException exception) {
            throw toProductPortException(OperationType.RESTORE_STOCK, exception);
        }
    }

    private ProductPortException toProductPortException(
            OperationType operationType,
            RestClientException exception
    ) {
        if (exception instanceof ProductApiException productApiException) {
            ProductErrorResponse errorResponse = productApiException.getErrorResponse();
            String message = errorResponse.message() != null ? errorResponse.message() : exception.getMessage();
            return new ProductPortException(
                    toOrderFailCode(operationType, errorResponse.failCode()), message, exception);
        }
        return new ProductPortException(fallbackFailCode(operationType), exception.getMessage(), exception);
    }

    private OrderFailCode toOrderFailCode(OperationType operationType, String failCode) {
        if (failCode == null) {
            return fallbackFailCode(operationType);
        }
        return switch (failCode) {
            case "DROP_SOLD_OUT", "SOLD_OUT" -> OrderFailCode.SOLD_OUT;
            case "DROP_NOT_OPEN", "NOT_OPEN" -> OrderFailCode.DROP_NOT_OPEN;
            case "DROP_CLOSED", "CLOSED" -> OrderFailCode.DROP_CLOSED;
            case "DROP_LIMIT_EXCEEDED", "LIMIT_EXCEEDED" -> OrderFailCode.LIMIT_EXCEEDED;
            default -> fallbackFailCode(operationType);
        };
    }

    private OrderFailCode fallbackFailCode(OperationType operationType) {
        return operationType == OperationType.RESTORE_STOCK
                ? OrderFailCode.STOCK_ROLLBACK_FAILED
                : OrderFailCode.PRODUCT_INTEGRATION_FAILED;
    }
}

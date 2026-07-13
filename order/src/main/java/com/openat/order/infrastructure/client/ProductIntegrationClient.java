package com.openat.order.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.ProductPortDtos.OperationType;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import feign.FeignException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductIntegrationClient implements ProductIntegrationPort {

    private final ProductInternalApiClient productInternalApiClient;
    private final ObjectMapper objectMapper;

    @Override
    public OrderSnapshotInfo fetchOrderSnapshot(UUID dropId) {
        try {
            OrderSnapshotResponse response = productInternalApiClient.fetchOrderSnapshot(dropId);
            return new OrderSnapshotInfo(
                    response.productId(),
                    response.sellerId(),
                    response.unitPrice()
            );
        } catch (FeignException e) {
            throw toProductPortException(OperationType.FETCH_ORDER_SNAPSHOT, e);
        }
    }

    @Override
    public void decreaseStock(UUID dropId, StockDecreaseCommand command) {
        try {
            productInternalApiClient.decreaseStock(
                    dropId,
                    new StockChangeRequest(command.orderId(), command.buyerId(), command.quantity())
            );
        } catch (FeignException e) {
            throw toProductPortException(OperationType.DECREASE_STOCK, e);
        }
    }

    @Override
    public void restoreStock(UUID dropId, StockRestoreCommand command) {
        try {
            productInternalApiClient.restoreStock(
                    dropId,
                    new StockChangeRequest(command.orderId(), command.buyerId(), command.quantity())
            );
        } catch (FeignException e) {
            throw toProductPortException(OperationType.RESTORE_STOCK, e);
        }
    }

    private ProductPortException toProductPortException(OperationType operationType, FeignException e) {
        ProductErrorResponse errorResponse = readErrorResponse(e);
        String failCode = errorResponse.failCode();
        String message = errorResponse.message() != null ? errorResponse.message() : e.getMessage();
        return new ProductPortException(toOrderFailCode(operationType, failCode), message, e);
    }

    private ProductErrorResponse readErrorResponse(FeignException e) {
        try {
            return objectMapper.readValue(e.contentUTF8(), ProductErrorResponse.class);
        } catch (Exception ignored) {
            return new ProductErrorResponse(null, null, e.getMessage());
        }
    }

    private OrderFailCode toOrderFailCode(OperationType operationType, String failCode) {
        if ("DROP_SOLD_OUT".equals(failCode) || "SOLD_OUT".equals(failCode)) {
            return OrderFailCode.SOLD_OUT;
        }
        if ("DROP_NOT_OPEN".equals(failCode) || "NOT_OPEN".equals(failCode)) {
            return OrderFailCode.DROP_NOT_OPEN;
        }
        if ("DROP_CLOSED".equals(failCode) || "CLOSED".equals(failCode)) {
            return OrderFailCode.DROP_CLOSED;
        }
        if ("DROP_LIMIT_EXCEEDED".equals(failCode) || "LIMIT_EXCEEDED".equals(failCode)) {
            return OrderFailCode.LIMIT_EXCEEDED;
        }
        if (operationType == OperationType.RESTORE_STOCK) {
            return OrderFailCode.STOCK_ROLLBACK_FAILED;
        }
        return OrderFailCode.PRODUCT_INTEGRATION_FAILED;
    }
}

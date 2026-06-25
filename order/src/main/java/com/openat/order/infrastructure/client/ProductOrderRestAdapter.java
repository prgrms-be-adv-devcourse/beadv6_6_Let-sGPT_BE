package com.openat.order.infrastructure.client;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.ProductOrderSnapshot;
import com.openat.order.application.port.ProductOrderPort;
import com.openat.order.domain.error.OrderErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class ProductOrderRestAdapter implements ProductOrderPort {

    private final RestClient productRestClient;

    @Override
    public ProductOrderSnapshot getOrderSnapshot(UUID dropId) {
        try {
            ProductOrderSnapshotResponse response = productRestClient.get()
                    .uri("/internal/drops/{dropId}/order-snapshot", dropId)
                    .retrieve()
                    .body(ProductOrderSnapshotResponse.class);
            if (response == null) {
                throw new BusinessException(OrderErrorCode.PRODUCT_SNAPSHOT_FAILED);
            }
            return response.toInfo();
        } catch (RestClientException e) {
            throw new BusinessException(OrderErrorCode.PRODUCT_SNAPSHOT_FAILED, e.getMessage(), e);
        }
    }

    @Override
    public void decreaseStock(UUID dropId, UUID orderId, int quantity, String idempotencyKey) {
        callStock(
                "/internal/drops/{dropId}/stock/decrease",
                dropId,
                orderId,
                quantity,
                idempotencyKey,
                OrderErrorCode.STOCK_DECREASE_FAILED);
    }

    @Override
    public void restoreStock(UUID dropId, UUID orderId, int quantity, String idempotencyKey) {
        callStock(
                "/internal/drops/{dropId}/stock/restore",
                dropId,
                orderId,
                quantity,
                idempotencyKey,
                OrderErrorCode.STOCK_RESTORE_FAILED);
    }

    private void callStock(
            String uri,
            UUID dropId,
            UUID orderId,
            int quantity,
            String idempotencyKey,
            OrderErrorCode errorCode) {
        try {
            productRestClient.post()
                    .uri(uri, dropId)
                    .body(new StockRequest(orderId, quantity, idempotencyKey))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw mapStockFailure(e, errorCode);
        } catch (RestClientException e) {
            throw new BusinessException(errorCode, e.getMessage(), e);
        }
    }

    private BusinessException mapStockFailure(RestClientResponseException e, OrderErrorCode fallback) {
        String body = e.getResponseBodyAsString();
        if (body.contains("\"SOLD_OUT\"")) {
            return new BusinessException(OrderErrorCode.SOLD_OUT);
        }
        if (body.contains("\"NOT_OPEN\"")) {
            return new BusinessException(OrderErrorCode.NOT_OPEN);
        }
        if (body.contains("\"LIMIT_EXCEEDED\"")) {
            return new BusinessException(OrderErrorCode.LIMIT_EXCEEDED);
        }
        return new BusinessException(fallback, e.getMessage(), e);
    }

    private record ProductOrderSnapshotResponse(
            UUID dropId,
            UUID productId,
            UUID sellerId,
            String productName,
            long unitPrice) {

        ProductOrderSnapshot toInfo() {
            return new ProductOrderSnapshot(dropId, productId, sellerId, productName, unitPrice);
        }
    }

    private record StockRequest(UUID orderId, int quantity, String idempotencyKey) {
    }
}

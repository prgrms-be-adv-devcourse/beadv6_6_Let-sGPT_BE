package com.openat.order.infrastructure.client;

import static com.openat.order.infrastructure.client.ProductPortDtos.OperationType.DECREASE_STOCK;
import static com.openat.order.infrastructure.client.ProductPortDtos.OperationType.FETCH_ORDER_SNAPSHOT;
import static com.openat.order.infrastructure.client.ProductPortDtos.OperationType.RESTORE_STOCK;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.OperationType;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

@Component
public class ProductIntegrationPortAdapter implements ProductIntegrationPort {

    private static final String ORDER_SNAPSHOT_PATH = "/internal/drops/{dropId}/order-snapshot";
    private static final String STOCK_DECREASE_PATH = "/internal/drops/{dropId}/stock/decrease";
    private static final String STOCK_RESTORE_PATH = "/internal/drops/{dropId}/stock/restore";

    private final RestClient productClient;
    private final ObjectMapper objectMapper;

    public ProductIntegrationPortAdapter(RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${services.product.url}") String productServiceUrl) {
        this.productClient = restClientBuilder
                .baseUrl(removeTrailingSlash(productServiceUrl))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public OrderSnapshotInfo fetchOrderSnapshot(UUID dropId) {
        try {
            OrderSnapshotResponse response = productClient.get()
                    .uri(ORDER_SNAPSHOT_PATH, dropId)
                    .retrieve()
                    .body(OrderSnapshotResponse.class);
            if (response == null) {
                throw new ProductPortException(OrderFailCode.PAYMENT_FAILED, "상품 스냅샷 응답이 비어 있습니다.");
            }
            return new OrderSnapshotInfo(
                    response.dropId(),
                    response.productId(),
                    response.sellerId(),
                    response.productName(),
                    response.unitPrice());
        } catch (RestClientResponseException e) {
            throw resolveFailure(e, FETCH_ORDER_SNAPSHOT);
        } catch (RestClientException e) {
            throw new ProductPortException(OrderFailCode.PAYMENT_FAILED, e.getMessage(), e);
        }
    }

    @Override
    public void decreaseStock(UUID dropId, StockDecreaseCommand command) {
        StockChangeRequest request = new StockChangeRequest(command.orderId(), command.quantity(), command.idempotencyKey());
        try {
            productClient.post()
                    .uri(STOCK_DECREASE_PATH, dropId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw resolveFailure(e, DECREASE_STOCK);
        } catch (RestClientException e) {
            throw new ProductPortException(OrderFailCode.PAYMENT_FAILED, e.getMessage(), e);
        }
    }

    @Override
    public void restoreStock(UUID dropId, StockRestoreCommand command) {
        StockChangeRequest request = new StockChangeRequest(command.orderId(), command.quantity(), command.idempotencyKey());
        try {
            productClient.post()
                    .uri(STOCK_RESTORE_PATH, dropId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw resolveFailure(e, RESTORE_STOCK);
        } catch (RestClientException e) {
            throw new ProductPortException(OrderFailCode.STOCK_ROLLBACK_FAILED, e.getMessage(), e);
        }
    }

    private ProductPortException resolveFailure(RestClientResponseException e, OperationType operationType) {
        ProductErrorResponse errorResponse = parseErrorBody(e.getResponseBodyAsString());
        String failMessage = resolveMessage(errorResponse, e);
        OrderFailCode failCode = mapToOrderFailCode(errorResponse != null ? errorResponse.failCode() : null, operationType);
        return new ProductPortException(failCode, failMessage, e);
    }

    private ProductErrorResponse parseErrorBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(responseBody, ProductErrorResponse.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String resolveMessage(ProductErrorResponse errorResponse, RestClientResponseException e) {
        if (errorResponse != null && errorResponse.message() != null && !errorResponse.message().isBlank()) {
            return errorResponse.message();
        }
        return e.getStatusText();
    }

    private OrderFailCode mapToOrderFailCode(String rawCode, OperationType operationType) {
        String code = rawCode == null ? "" : rawCode.toUpperCase();
        return switch (code) {
            case "SOLD_OUT" -> OrderFailCode.SOLD_OUT;
            case "NOT_OPEN", "DROP_NOT_OPEN" -> OrderFailCode.DROP_NOT_OPEN;
            case "LIMIT_EXCEEDED" -> OrderFailCode.LIMIT_EXCEEDED;
            case "STOCK_ROLLBACK_FAILED" -> OrderFailCode.STOCK_ROLLBACK_FAILED;
            case "PAYMENT_FAILED" -> OrderFailCode.PAYMENT_FAILED;
            default -> operationType == RESTORE_STOCK
                    ? OrderFailCode.STOCK_ROLLBACK_FAILED
                    : OrderFailCode.PAYMENT_FAILED;
        };
    }

    private String removeTrailingSlash(String productServiceUrl) {
        if (productServiceUrl == null) {
            return "";
        }
        return productServiceUrl.endsWith("/")
                ? productServiceUrl.substring(0, productServiceUrl.length() - 1)
                : productServiceUrl;
    }
}

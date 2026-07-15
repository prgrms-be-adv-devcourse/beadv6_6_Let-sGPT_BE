package com.openat.order.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ProductInternalApiClient {

    private final RestClient productRestClient;
    private final ObjectMapper objectMapper;

    public ProductInternalApiClient(
            @Qualifier("productRestClient") RestClient productRestClient,
            ObjectMapper objectMapper
    ) {
        this.productRestClient = productRestClient;
        this.objectMapper = objectMapper;
    }

    public OrderSnapshotResponse fetchOrderSnapshot(UUID dropId) {
        OrderSnapshotResponse response = productRestClient.get()
                .uri("/internal/drops/{dropId}/order-snapshot", dropId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwProductApiException)
                .body(OrderSnapshotResponse.class);
        if (response == null) {
            throw new RestClientException(
                    "Product order snapshot response body is empty: dropId=" + dropId);
        }
        return response;
    }

    public void decreaseStock(UUID dropId, StockChangeRequest request) {
        postStockChange("/internal/drops/{dropId}/stock-deductions", dropId, request);
    }

    public void restoreStock(UUID dropId, StockChangeRequest request) {
        postStockChange("/internal/drops/{dropId}/stock-rollbacks", dropId, request);
    }

    private void postStockChange(String path, UUID dropId, StockChangeRequest request) {
        productRestClient.post()
                .uri(path, dropId)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::throwProductApiException)
                .toBodilessEntity();
    }

    private void throwProductApiException(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) throws IOException {
        String responseBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        ProductErrorResponse errorResponse = parseErrorResponse(responseBody);
        throw new ProductApiException(response.getStatusCode(), errorResponse, responseBody);
    }

    private ProductErrorResponse parseErrorResponse(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, ProductErrorResponse.class);
        } catch (Exception ignored) {
            return new ProductErrorResponse(null, null, null);
        }
    }
}

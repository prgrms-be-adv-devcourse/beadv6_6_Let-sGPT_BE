package com.openat.payment.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// X-3 — GET /internal/v1/orders/{orderId} 실연동. OrderValidationClient의 유일한 구현체.
@Slf4j
@Component
public class RealOrderValidationClient implements OrderValidationClient {

    private final RestClient orderRestClient;

    public RealOrderValidationClient(@Qualifier("orderRestClient") RestClient orderRestClient) {
        this.orderRestClient = orderRestClient;
    }

    @Override
    public OrderValidationResult validate(UUID orderId, UUID claimedMemberId, Long claimedAmount) {
        try {
            OrderResponse response = orderRestClient.get()
                    .uri("/internal/v1/orders/{orderId}", orderId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new OrderNotFoundException("Order not found: " + orderId);
                    })
                    .body(OrderResponse.class);

            if (response == null) {
                return new OrderValidationResult(claimedMemberId, claimedAmount, "UNKNOWN", false);
            }
            return new OrderValidationResult(response.memberId(), response.amount(), response.status(), true);
        } catch (OrderNotFoundException e) {
            return new OrderValidationResult(claimedMemberId, claimedAmount, "NOT_FOUND", false);
        } catch (RestClientException e) {
            log.error("[RealOrderValidationClient] 주문 조회 실패: orderId={}", orderId, e);
            throw e;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderResponse(UUID memberId, long amount, String status) {
    }

    private static class OrderNotFoundException extends RuntimeException {
        OrderNotFoundException(String message) {
            super(message);
        }
    }
}

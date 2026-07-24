package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import com.openat.recommendation.application.port.out.OrderSignalClient;
import com.openat.recommendation.domain.model.PurchaseSignal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderSignalRestClient implements OrderSignalClient {

  private final RestClient restClient;
  private final int purchaseLimit;

  public OrderSignalRestClient(
      @Qualifier("orderRestClient") RestClient restClient,
      @Value("${recommendation.weights.purchase-limit}") int purchaseLimit) {
    this.restClient = restClient;
    this.purchaseLimit = purchaseLimit;
  }

  @Override
  public List<PurchaseSignal> getPurchaseSignals(UUID memberId) {
    List<PurchaseSignalResponse> response =
        requireBody(
            restClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/internal/v1/orders/purchase-signals")
                            .queryParam("memberId", memberId)
                            .queryParam("limit", purchaseLimit)
                            .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}),
            "Order purchase signal response body is empty");
    return response.stream().map(PurchaseSignalResponse::toDomain).toList();
  }

  record PurchaseSignalResponse(
      UUID productId, long orderCount, long totalQuantity, Instant lastOrderedAt) {
    PurchaseSignal toDomain() {
      return new PurchaseSignal(productId, orderCount, totalQuantity, lastOrderedAt);
    }
  }
}

package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductDetailClient {

  private final RestClient restClient;

  public ProductDetailClient(@Qualifier("productRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  public ProductDetailResponse getProduct(UUID productId) {
    return requireBody(
        restClient
            .get()
            .uri("/api/v1/products/{id}", productId)
            .retrieve()
            .body(ProductDetailResponse.class),
        "Product detail response body is empty");
  }

  public record ProductDetailResponse(
      UUID id,
      UUID sellerId,
      String sellerName,
      String name,
      String description,
      UUID categoryId,
      String categoryName,
      Long price,
      String thumbnailKey,
      List<String> imageKeys,
      Instant createdAt) {}
}

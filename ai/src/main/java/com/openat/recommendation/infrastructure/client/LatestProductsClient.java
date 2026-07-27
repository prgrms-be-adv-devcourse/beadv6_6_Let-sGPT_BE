package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 드롭 상태와 무관한 "최신 상품" 목록. 존재하고 항상 동작하는 {@code GET /api/v1/products} 를
 * 쓴다. 최후 폴백(홈이 절대 비지 않도록)의 소스이며, 스케줄로 미리 채워 요청당 지연이 없다.
 */
@Component
public class LatestProductsClient {

  private final RestClient restClient;

  public LatestProductsClient(@Qualifier("productRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  public List<LatestProduct> latest(int size) {
    ProductPage page =
        requireBody(
            restClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/api/v1/products")
                            .queryParam("page", 0)
                            .queryParam("size", size)
                            .queryParam("sort", "createdAt,desc")
                            .build())
                .retrieve()
                .body(ProductPage.class),
            "Latest products response body is empty");
    return page.content() == null ? List.of() : page.content();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProductPage(List<LatestProduct> content) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record LatestProduct(
      UUID id, String name, String sellerName, Long price, String thumbnailKey) {}
}

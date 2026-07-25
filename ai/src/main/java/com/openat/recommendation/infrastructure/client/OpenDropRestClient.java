package com.openat.recommendation.infrastructure.client;

import static com.openat.recommendation.infrastructure.client.RestClientResponses.requireBody;

import com.openat.common.response.PageResponse;
import com.openat.recommendation.application.port.out.OpenDropClient;
import com.openat.recommendation.domain.model.DropMeta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenDropRestClient implements OpenDropClient {

  private final RestClient restClient;
  private final int pageSize;

  public OpenDropRestClient(
      @Qualifier("productRestClient") RestClient restClient,
      @Value("${recommendation.drop-cache.page-size}") int pageSize) {
    this.restClient = restClient;
    this.pageSize = pageSize;
  }

  @Override
  public List<DropMeta> getAllOpenDrops() {
    List<DropMeta> drops = new ArrayList<>();
    int page = 0;
    int totalPages;

    do {
      PageResponse<DropResponse> response = getPage(page);
      if (response.content() == null) {
        throw new RestClientException("Product open drop response content is empty");
      }
      drops.addAll(
          response.content().stream()
              .filter(drop -> drop.remainingQuantity() > 0)
              .map(DropResponse::toDomain)
              .toList());
      totalPages = response.totalPages();
      page++;
    } while (page < totalPages);

    return List.copyOf(drops);
  }

  private PageResponse<DropResponse> getPage(int page) {
    return requireBody(
        restClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/api/v1/drops")
                        .queryParam("status", "OPEN")
                        .queryParam("page", page)
                        .queryParam("size", pageSize)
                        .build())
            .retrieve()
            .body(new ParameterizedTypeReference<>() {}),
        "Product open drop response body is empty");
  }

  record DropResponse(
      UUID id,
      UUID productId,
      String productName,
      String sellerName,
      UUID categoryId,
      String categoryName,
      String thumbnailKey,
      long dropPrice,
      int totalQuantity,
      int remainingQuantity,
      String status,
      Instant openAt,
      Instant closeAt) {
    DropMeta toDomain() {
      return new DropMeta(
          id, productId, productName, sellerName, dropPrice, thumbnailKey, categoryId, closeAt);
    }
  }
}

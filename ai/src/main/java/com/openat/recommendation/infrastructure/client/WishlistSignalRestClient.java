package com.openat.recommendation.infrastructure.client;

import com.openat.common.auth.UserHeaders;
import com.openat.common.response.PageResponse;
import com.openat.recommendation.application.port.out.WishlistSignalClient;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class WishlistSignalRestClient implements WishlistSignalClient {

  private final RestClient restClient;
  private final int wishlistLimit;

  public WishlistSignalRestClient(
      @Qualifier("memberRestClient") RestClient restClient,
      @Value("${recommendation.weights.wishlist-limit}") int wishlistLimit) {
    this.restClient = restClient;
    this.wishlistLimit = wishlistLimit;
  }

  @Override
  public List<UUID> getWishlistProductIds(UUID memberId) {
    PageResponse<WishlistItemResponse> response =
        restClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder
                        .path("/api/v1/wishlist")
                        .queryParam("page", 0)
                        .queryParam("size", wishlistLimit)
                        .build())
            .header(UserHeaders.USER_ID, memberId.toString())
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    if (response == null) {
      throw new RestClientException("Member wishlist response body is empty");
    }
    return response.content().stream().map(WishlistItemResponse::productId).toList();
  }

  record WishlistItemResponse(UUID productId) {}
}

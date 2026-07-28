package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.openat.recommendation.application.service.RecommendationResponse.Product;
import com.openat.recommendation.infrastructure.client.LatestProductsClient;
import com.openat.recommendation.infrastructure.client.LatestProductsClient.LatestProduct;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LastResortProductsCacheTest {

  @Mock LatestProductsClient latestProductsClient;

  @Test
  void refresh_whenClientIgnoresRequestedSize_clampsSnapshot() {
    List<LatestProduct> tooMany = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      tooMany.add(latest(1000L));
    }
    when(latestProductsClient.latest(3)).thenReturn(tooMany);
    LastResortProductsCache cache = new LastResortProductsCache(latestProductsClient, 3);

    cache.refresh();

    assertThat(cache.get()).hasSize(3);
  }

  @Test
  void refresh_dropsPricelessProductsWithoutConsumingSlots() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(latestProductsClient.latest(2))
        .thenReturn(
            List.of(
                new LatestProduct(UUID.randomUUID(), "가격없음", "판매자", null, "thumb"),
                new LatestProduct(first, "상품1", "판매자", 100L, "thumb"),
                new LatestProduct(second, "상품2", "판매자", 200L, "thumb")));
    LastResortProductsCache cache = new LastResortProductsCache(latestProductsClient, 2);

    cache.refresh();

    assertThat(cache.get()).extracting(Product::productId).containsExactly(first, second);
    // 드롭 무관 상품이므로 dropId는 null이다(프런트는 상품 페이지로 보낸다).
    assertThat(cache.get()).extracting(Product::dropId).containsOnlyNulls();
  }

  @Test
  void refresh_whenClientFails_keepsPreviousSnapshot() {
    when(latestProductsClient.latest(1))
        .thenReturn(List.of(latest(500L)))
        .thenThrow(new RuntimeException("product service down"));
    LastResortProductsCache cache = new LastResortProductsCache(latestProductsClient, 1);
    cache.refresh();
    List<Product> firstSnapshot = cache.get();

    cache.refresh();

    assertThat(cache.get()).isEqualTo(firstSnapshot).hasSize(1);
  }

  private LatestProduct latest(Long price) {
    return new LatestProduct(UUID.randomUUID(), "최신상품", "판매자", price, "thumb");
  }
}

package com.openat.recommendation.application.service;

import java.util.List;
import java.util.UUID;

public record RecommendationResponse(List<Section> sections) {

  public static RecommendationResponse empty() {
    return new RecommendationResponse(List.of());
  }

  public record Section(String title, List<Product> products) {}

  public record Product(
      UUID productId, String name, String sellerName, long price, String thumbnailUrl) {}
}

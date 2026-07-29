package com.openat.recommendation.application.service;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record RecommendationResponse(List<Section> sections) {

  public static RecommendationResponse empty() {
    return new RecommendationResponse(List.of());
  }

  public record Section(String title, List<Product> products) {}

  /**
   * {@code dropId}가 링크 계약이다 — 값이 있으면 드롭 페이지, null이면 상품 페이지로 보낸다.
   *
   * <p>{@code price}도 이 구분을 따른다. 필드를 더해도 JSON은 이름 기준이라 옛 캐시와 호환된다.
   */
  public record Product(
      UUID productId,
      UUID dropId,
      String name,
      String sellerName,
      long price,
      @Schema(description = "썸네일 오브젝트 키. 완성된 URL이 아님") String thumbnailUrl) {}
}

package com.openat.recommendation.application.service;

import java.util.List;
import java.util.UUID;

public record RecommendationResponse(List<Section> sections) {

  public static RecommendationResponse empty() {
    return new RecommendationResponse(List.of());
  }

  public record Section(String title, List<Product> products) {}

  /**
   * 추천 카드 하나. {@code dropId}는 nullable이고, 이 값이 곧 링크 계약이다 — 값이 있으면 그 드롭
   * 페이지로, null이면 열린 드롭이 없는 상품이라 상품 페이지로 보낸다. {@code price}도 이 구분을
   * 따라간다(드롭이면 드롭가, 아니면 상품 정가).
   *
   * <p>필드를 추가할 때 순서를 바꿔도 JSON 역직렬화는 이름 기준이라 옛 캐시와 호환된다. 누락된
   * {@code dropId}는 null이 된다.
   */
  public record Product(
      UUID productId,
      UUID dropId,
      String name,
      String sellerName,
      long price,
      String thumbnailUrl) {}
}

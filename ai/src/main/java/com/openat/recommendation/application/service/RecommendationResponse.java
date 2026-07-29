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
   * 추천 카드 하나. {@code dropId}는 nullable이고, 이 값이 곧 링크 계약이다 — 값이 있으면 그 드롭
   * 페이지로, null이면 열린 드롭이 없는 상품이라 상품 페이지로 보낸다. {@code price}도 이 구분을
   * 따라간다(드롭이면 드롭가, 아니면 상품 정가).
   *
   * <p>필드를 추가할 때 순서를 바꿔도 JSON 역직렬화는 이름 기준이라 옛 캐시와 호환된다. 누락된
   * {@code dropId}는 null이 된다.
   *
   * <p>{@code thumbnailUrl}은 이름과 달리 완성된 URL이 아니라 오브젝트 스토리지 키다(상품 상세의
   * {@code thumbnailKey}, 드롭 메타의 {@code thumbnailKey}를 그대로 담는다). 이름을 바꾸면 이름
   * 기준으로 역직렬화하는 Redis 결과 캐시가 배포 즉시 전면 미스가 되므로 유지한다.
   */
  public record Product(
      UUID productId,
      UUID dropId,
      String name,
      String sellerName,
      long price,
      @Schema(description = "썸네일 오브젝트 키. 완성된 URL이 아니다 — 클라이언트가 베이스 URL과 조합한다")
          String thumbnailUrl) {}
}

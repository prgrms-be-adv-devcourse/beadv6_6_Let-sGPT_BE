package com.openat.recommendation.application.service;

import com.openat.recommendation.infrastructure.cache.RecommendationResultCache;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

enum RecommendationMode {
  HOME(3, 4, 12, "주제에 따라 1~3개 그룹으로", "각 그룹은 최대 4개"),
  DETAIL(1, 6, 6, "최대 1개의 주제 그룹으로", "한 그룹은 최대 6개");

  private final int maxSections;
  private final int maxProductsPerSection;
  private final int maxProductsTotal;
  private final String promptGrouping;
  private final String promptProductLimit;

  RecommendationMode(
      int maxSections,
      int maxProductsPerSection,
      int maxProductsTotal,
      String promptGrouping,
      String promptProductLimit) {
    this.maxSections = maxSections;
    this.maxProductsPerSection = maxProductsPerSection;
    this.maxProductsTotal = maxProductsTotal;
    this.promptGrouping = promptGrouping;
    this.promptProductLimit = promptProductLimit;
  }

  static RecommendationMode fromProductId(UUID productId) {
    return productId == null ? HOME : DETAIL;
  }

  int maxSections() {
    return maxSections;
  }

  int maxProductsPerSection() {
    return maxProductsPerSection;
  }

  int maxProductsTotal() {
    return maxProductsTotal;
  }

  String promptGrouping() {
    return promptGrouping;
  }

  String promptProductLimit() {
    return promptProductLimit;
  }

  boolean isHome() {
    return this == HOME;
  }

  /** 메트릭 태그 값. */
  String tag() {
    return name().toLowerCase(Locale.ROOT);
  }

  // 캐시 키 분기를 모드로 일원화한다. 홈은 CurrentMember로 회원 id를 구하되, 잘못된 id는
  // 익명으로 강등돼 Optional.empty가 되고(캐시만 건너뜀), 상세는 상품 id로 공유 키를 만든다.
  Optional<String> cacheKey(RecommendationResultCache resultCache, UUID productId) {
    return switch (this) {
      case HOME -> CurrentMember.id().map(memberId -> resultCache.cacheKey(null, memberId));
      case DETAIL -> Optional.of(resultCache.cacheKey(productId, null));
    };
  }
}

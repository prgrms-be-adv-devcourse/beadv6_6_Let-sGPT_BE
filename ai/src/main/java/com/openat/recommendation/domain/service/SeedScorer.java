package com.openat.recommendation.domain.service;

import com.openat.recommendation.domain.model.PurchaseSignal;
import com.openat.recommendation.domain.model.Seed;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SeedScorer {

  // 검색 API가 score를 요구해 채우는 더미 값 — 상세 시드는 1개뿐이라 순위에 영향이 없다.
  private static final double CURRENT_PRODUCT_SCORE = 0.9;

  private final double wishlistScore;
  private final double purchaseBaseScore;
  private final double purchaseIncrement;
  private final double purchaseMaxScore;
  private final int purchaseLimit;
  private final int wishlistLimit;

  public SeedScorer(
      @Value("${recommendation.weights.wishlist-score}") double wishlistScore,
      @Value("${recommendation.weights.purchase-base-score}") double purchaseBaseScore,
      @Value("${recommendation.weights.purchase-increment}") double purchaseIncrement,
      @Value("${recommendation.weights.purchase-max-score}") double purchaseMaxScore,
      @Value("${recommendation.weights.purchase-limit}") int purchaseLimit,
      @Value("${recommendation.weights.wishlist-limit}") int wishlistLimit) {
    this.wishlistScore = wishlistScore;
    this.purchaseBaseScore = purchaseBaseScore;
    this.purchaseIncrement = purchaseIncrement;
    this.purchaseMaxScore = purchaseMaxScore;
    this.purchaseLimit = purchaseLimit;
    this.wishlistLimit = wishlistLimit;
  }

  public List<Seed> scoreSignals(
      List<PurchaseSignal> purchaseSignals, List<UUID> wishlistProductIds) {
    Map<UUID, Seed> merged = new LinkedHashMap<>();
    purchaseSignals.stream()
        .limit(purchaseLimit)
        .map(signal -> new Seed(signal.productId(), purchaseScore(signal.orderCount()), true))
        .forEach(seed -> merged.putIfAbsent(seed.productId(), seed));
    wishlistProductIds.stream()
        .limit(wishlistLimit)
        .map(productId -> new Seed(productId, wishlistScore, false))
        .forEach(seed -> merged.putIfAbsent(seed.productId(), seed));
    return List.copyOf(merged.values());
  }

  public List<Seed> currentProductSeed(UUID currentProductId) {
    return List.of(new Seed(currentProductId, CURRENT_PRODUCT_SCORE, false));
  }

  public double purchaseScore(long orderCount) {
    return Math.min(purchaseBaseScore + (orderCount - 1) * purchaseIncrement, purchaseMaxScore);
  }
}

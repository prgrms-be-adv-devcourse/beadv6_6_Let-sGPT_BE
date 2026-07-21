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

  private final double currentProductScore;
  private final double wishlistScore;
  private final double purchaseBaseScore;
  private final double purchaseIncrement;
  private final double purchaseMaxScore;
  private final int purchaseLimit;
  private final int wishlistLimit;

  public SeedScorer(
      @Value("${recommendation.weights.current-product-score}") double currentProductScore,
      @Value("${recommendation.weights.wishlist-score}") double wishlistScore,
      @Value("${recommendation.weights.purchase-base-score}") double purchaseBaseScore,
      @Value("${recommendation.weights.purchase-increment}") double purchaseIncrement,
      @Value("${recommendation.weights.purchase-max-score}") double purchaseMaxScore,
      @Value("${recommendation.weights.purchase-limit}") int purchaseLimit,
      @Value("${recommendation.weights.wishlist-limit}") int wishlistLimit) {
    this.currentProductScore = currentProductScore;
    this.wishlistScore = wishlistScore;
    this.purchaseBaseScore = purchaseBaseScore;
    this.purchaseIncrement = purchaseIncrement;
    this.purchaseMaxScore = purchaseMaxScore;
    this.purchaseLimit = purchaseLimit;
    this.wishlistLimit = wishlistLimit;
  }

  public List<Seed> scoreSignals(
      List<PurchaseSignal> purchaseSignals, List<UUID> wishlistProductIds) {
    Map<UUID, PrioritizedSeed> merged = new LinkedHashMap<>();
    purchaseSignals.stream()
        .limit(purchaseLimit)
        .map(signal -> new Seed(signal.productId(), purchaseScore(signal.orderCount()), true))
        .forEach(seed -> merge(merged, seed, SignalType.PURCHASE));
    wishlistProductIds.stream()
        .limit(wishlistLimit)
        .map(productId -> new Seed(productId, wishlistScore, false))
        .forEach(seed -> merge(merged, seed, SignalType.WISHLIST));
    return merged.values().stream().map(PrioritizedSeed::seed).toList();
  }

  public List<Seed> mergeCurrentProduct(List<Seed> baseSeeds, UUID currentProductId) {
    Map<UUID, PrioritizedSeed> merged = new LinkedHashMap<>();
    merge(
        merged, new Seed(currentProductId, currentProductScore, false), SignalType.CURRENT_PRODUCT);
    baseSeeds.forEach(
        seed -> merge(merged, seed, seed.buy() ? SignalType.PURCHASE : SignalType.WISHLIST));
    return merged.values().stream().map(PrioritizedSeed::seed).toList();
  }

  public double purchaseScore(long orderCount) {
    return Math.min(purchaseBaseScore + (orderCount - 1) * purchaseIncrement, purchaseMaxScore);
  }

  private void merge(Map<UUID, PrioritizedSeed> merged, Seed candidate, SignalType signalType) {
    PrioritizedSeed prioritizedCandidate = new PrioritizedSeed(candidate, signalType);
    merged.merge(
        candidate.productId(),
        prioritizedCandidate,
        (existing, incoming) ->
            incoming.signalType().priority > existing.signalType().priority ? incoming : existing);
  }

  private record PrioritizedSeed(Seed seed, SignalType signalType) {}

  private enum SignalType {
    WISHLIST(1),
    PURCHASE(2),
    CURRENT_PRODUCT(3);

    private final int priority;

    SignalType(int priority) {
      this.priority = priority;
    }
  }
}

package com.openat.recommendation.application.service;

import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import com.openat.recommendation.application.port.out.OrderSignalClient;
import com.openat.recommendation.application.port.out.WishlistSignalClient;
import com.openat.recommendation.domain.model.PurchaseSignal;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.domain.service.SeedScorer;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecommendationSeedService {

  private static final Logger log = LoggerFactory.getLogger(RecommendationSeedService.class);

  private final OrderSignalClient orderSignalClient;
  private final WishlistSignalClient wishlistSignalClient;
  private final SeedScorer seedScorer;
  private final SeedWeightsCache seedWeightsCache;
  private final Duration partialTtl;

  public RecommendationSeedService(
      OrderSignalClient orderSignalClient,
      WishlistSignalClient wishlistSignalClient,
      SeedScorer seedScorer,
      SeedWeightsCache seedWeightsCache,
      @Value("${recommendation.weights.partial-ttl:10m}") Duration partialTtl) {
    this.orderSignalClient = orderSignalClient;
    this.wishlistSignalClient = wishlistSignalClient;
    this.seedScorer = seedScorer;
    this.seedWeightsCache = seedWeightsCache;
    this.partialTtl = partialTtl;
  }

  public List<Seed> collect(UUID currentProductId) {
    UserContext context = UserContextHolder.get();
    if (context == null) {
      return currentProductId == null
          ? List.of()
          : seedScorer.mergeCurrentProduct(List.of(), currentProductId);
    }

    UUID memberId = UUID.fromString(context.userId());
    List<Seed> baseSeeds =
        seedWeightsCache.find(memberId).orElseGet(() -> refreshWeightsCache(memberId));
    return currentProductId == null
        ? baseSeeds
        : seedScorer.mergeCurrentProduct(baseSeeds, currentProductId);
  }

  public List<Seed> refreshWeightsCache(UUID memberId) {
    Optional<List<PurchaseSignal>> purchaseSignals = getPurchaseSignals(memberId);
    Optional<List<UUID>> wishlistProductIds = getWishlistProductIds(memberId);
    List<Seed> baseSeeds =
        seedScorer.scoreSignals(
            purchaseSignals.orElse(List.of()), wishlistProductIds.orElse(List.of()));
    if (purchaseSignals.isPresent() && wishlistProductIds.isPresent()) {
      seedWeightsCache.save(memberId, baseSeeds, SeedWeightsCache.FULL_TTL);
    } else if (purchaseSignals.isPresent() || wishlistProductIds.isPresent()) {
      Optional<List<Seed>> cachedSeeds = seedWeightsCache.find(memberId);
      if (cachedSeeds.isPresent()) {
        return cachedSeeds.get();
      }
      seedWeightsCache.save(memberId, baseSeeds, partialTtl);
    } else {
      Optional<List<Seed>> cachedSeeds = seedWeightsCache.find(memberId);
      if (cachedSeeds.isPresent()) {
        return cachedSeeds.get();
      }
      log.warn(
          "Both order and member signal lookups failed; skipping weights cache write: memberId={}",
          memberId);
    }
    return baseSeeds;
  }

  private Optional<List<PurchaseSignal>> getPurchaseSignals(UUID memberId) {
    try {
      return Optional.of(orderSignalClient.getPurchaseSignals(memberId));
    } catch (Exception exception) {
      log.warn(
          "Failed to collect order signals; continuing without them: memberId={}",
          memberId,
          exception);
      return Optional.empty();
    }
  }

  private Optional<List<UUID>> getWishlistProductIds(UUID memberId) {
    try {
      return Optional.of(wishlistSignalClient.getWishlistProductIds(memberId));
    } catch (Exception exception) {
      log.warn(
          "Failed to collect member signals; continuing without them: memberId={}",
          memberId,
          exception);
      return Optional.empty();
    }
  }
}

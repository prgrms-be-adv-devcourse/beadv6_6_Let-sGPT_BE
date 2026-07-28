package com.openat.recommendation.application.service;

import com.openat.recommendation.application.port.out.OrderSignalClient;
import com.openat.recommendation.application.port.out.WishlistSignalClient;
import com.openat.recommendation.domain.model.PurchaseSignal;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.domain.service.SeedScorer;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache.SeedWeights;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
  private final Duration salvageMaxAge;
  private final Executor executor;
  private final RecommendationMetrics metrics;

  public RecommendationSeedService(
      OrderSignalClient orderSignalClient,
      WishlistSignalClient wishlistSignalClient,
      SeedScorer seedScorer,
      SeedWeightsCache seedWeightsCache,
      @Value("${recommendation.weights.partial-ttl:10m}") Duration partialTtl,
      @Value("${recommendation.weights.salvage-max-age:24h}") Duration salvageMaxAge,
      @Qualifier("recommendationExecutor") Executor executor,
      RecommendationMetrics metrics) {
    this.orderSignalClient = orderSignalClient;
    this.wishlistSignalClient = wishlistSignalClient;
    this.seedScorer = seedScorer;
    this.seedWeightsCache = seedWeightsCache;
    this.partialTtl = partialTtl;
    this.salvageMaxAge = salvageMaxAge;
    this.executor = executor;
    this.metrics = metrics;
  }

  public List<Seed> collect() {
    return CurrentMember.id()
        .map(
            memberId ->
                seedWeightsCache
                    .find(memberId)
                    .map(SeedWeights::seeds)
                    .orElseGet(() -> refreshWeightsCache(memberId)))
        .orElseGet(List::of);
  }

  public List<Seed> refreshWeightsCache(UUID memberId) {
    Instant startedAt = Instant.now();
    CompletableFuture<Optional<List<PurchaseSignal>>> purchaseSignalsFuture =
        CompletableFuture.supplyAsync(() -> getPurchaseSignals(memberId), executor);
    CompletableFuture<Optional<List<UUID>>> wishlistProductIdsFuture =
        CompletableFuture.supplyAsync(() -> getWishlistProductIds(memberId), executor);
    Optional<List<PurchaseSignal>> purchaseSignals = purchaseSignalsFuture.join();
    Optional<List<UUID>> wishlistProductIds = wishlistProductIdsFuture.join();
    List<Seed> freshSeeds =
        seedScorer.scoreSignals(
            purchaseSignals.orElse(List.of()), wishlistProductIds.orElse(List.of()));
    if (purchaseSignals.isPresent() && wishlistProductIds.isPresent()) {
      seedWeightsCache.save(
          memberId, SeedWeights.full(freshSeeds, startedAt), SeedWeightsCache.FULL_TTL);
      metrics.seedRefresh("full");
      return freshSeeds;
    }

    Optional<SeedWeights> cached = seedWeightsCache.find(memberId);
    if (purchaseSignals.isEmpty() && wishlistProductIds.isEmpty()) {
      metrics.seedRefresh("both-missing");
      if (cached.isPresent()) {
        return cached.get().seeds();
      }
      log.warn(
          "Both order and member signal lookups failed; skipping weights cache write: memberId={}",
          memberId);
      return freshSeeds;
    }

    // 한쪽만 실패: 새로 받은 쪽은 즉시 반영하고, 실패한 쪽은 기존 캐시에서 살려 온다. 기존 캐시를
    // 그대로 반환하면 방금 받은 변경(예: 새 찜)이 FULL_TTL 동안 묻힌다.
    boolean purchaseSucceeded = purchaseSignals.isPresent();
    metrics.seedRefresh(purchaseSucceeded ? "wishlist-missing" : "order-missing");
    // 신호를 받는 동안 다른 스레드가 완전 데이터를 저장했으면, 절반이 낡은 부분 데이터로 그것을
    // 짧은 TTL에 덮지 않는다. 그쪽이 우리 것보다 새롭고 완전하므로 그대로 쓴다.
    if (cached.isPresent() && supersedes(cached.get(), startedAt)) {
      metrics.seedSalvage("superseded");
      return cached.get().seeds();
    }

    // 살려 온 절반을 계속 물려주면 실패가 이어지는 동안 그 시드의 수명이 무한 연장된다. 상한을
    // 넘으면 실패한 쪽을 버리고 방금 받은 쪽만 남긴다.
    Optional<SeedWeights> salvageable = cached.filter(weights -> canSalvage(weights, startedAt));
    List<Seed> salvagedSeeds =
        salvageable.map(weights -> salvage(weights, purchaseSucceeded)).orElse(List.of());
    metrics.seedSalvage(salvageOutcome(cached, salvageable, salvagedSeeds));
    List<Seed> mergedSeeds =
        purchaseSucceeded ? merge(freshSeeds, salvagedSeeds) : merge(salvagedSeeds, freshSeeds);
    // 살려 온 절반은 낡았을 수 있으므로 완전 데이터인 척 FULL_TTL 동안 남기지 않고, 그 절반을
    // 처음 모은 시각을 물려줘 다음 부분 저장이 수명을 더 늘리지 못하게 한다.
    Instant collectedAt =
        salvagedSeeds.isEmpty() ? startedAt : salvageable.orElseThrow().collectedAt();
    seedWeightsCache.save(memberId, SeedWeights.partial(mergedSeeds, collectedAt), partialTtl);
    return mergedSeeds;
  }

  private String salvageOutcome(
      Optional<SeedWeights> cached, Optional<SeedWeights> salvageable, List<Seed> salvagedSeeds) {
    if (cached.isPresent() && salvageable.isEmpty()) {
      return "expired";
    }
    return salvagedSeeds.isEmpty() ? "none" : "merged";
  }

  /** 우리가 신호를 조회하기 시작한 뒤에 저장된 완전 데이터. 부분 데이터로 덮으면 손해다. */
  private boolean supersedes(SeedWeights cached, Instant startedAt) {
    return cached.complete() && cached.collectedAt().isAfter(startedAt);
  }

  /** 캐시에 담긴 가장 오래된 시드가 완전 데이터로 살아 있을 수 있는 기간을 넘지 않았는지. */
  private boolean canSalvage(SeedWeights cached, Instant startedAt) {
    return !startedAt.isAfter(cached.collectedAt().plus(salvageMaxAge));
  }

  /** 실패한 신호가 만들던 시드만 기존 캐시에서 꺼낸다. 성공한 쪽은 새 값으로 완전히 대체된다. */
  private List<Seed> salvage(SeedWeights cached, boolean purchaseSucceeded) {
    return cached.seeds().stream().filter(seed -> seed.buy() != purchaseSucceeded).toList();
  }

  /** SeedScorer와 같은 규칙: 같은 상품이 양쪽에 있으면 더 강한 신호인 구매 시드를 남긴다. */
  private List<Seed> merge(List<Seed> purchaseSeeds, List<Seed> wishlistSeeds) {
    Map<UUID, Seed> merged = new LinkedHashMap<>();
    purchaseSeeds.forEach(seed -> merged.putIfAbsent(seed.productId(), seed));
    wishlistSeeds.forEach(seed -> merged.putIfAbsent(seed.productId(), seed));
    return List.copyOf(merged.values());
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

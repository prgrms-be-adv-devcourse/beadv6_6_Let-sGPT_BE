package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.auth.UserContext;
import com.openat.common.auth.UserContextHolder;
import com.openat.recommendation.application.port.out.OrderSignalClient;
import com.openat.recommendation.application.port.out.WishlistSignalClient;
import com.openat.recommendation.domain.model.PurchaseSignal;
import com.openat.recommendation.domain.model.Seed;
import com.openat.recommendation.domain.service.SeedScorer;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache.CachedWeights;
import com.openat.recommendation.infrastructure.cache.SeedWeightsCache.SeedWeights;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationSeedServiceTest {

  private static final Duration PARTIAL_TTL = Duration.ofMinutes(10);
  private static final Duration SALVAGE_MAX_AGE = Duration.ofHours(24);

  @Mock private OrderSignalClient orderSignalClient;
  @Mock private WishlistSignalClient wishlistSignalClient;
  @Mock private SeedWeightsCache seedWeightsCache;
  private final SeedScorer seedScorer = new SeedScorer(0.3, 0.5, 0.1, 0.85, 20, 20);
  private final ExecutorService executor = Executors.newFixedThreadPool(4);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @BeforeEach
  void setUp() {
    lenient()
        .when(seedWeightsCache.findSnapshot(any()))
        .thenAnswer(
            invocation ->
                seedWeightsCache
                    .find(invocation.getArgument(0, UUID.class))
                    .map(weights -> new CachedWeights(weights, "cached-json")));
    lenient()
        .when(seedWeightsCache.saveIfUnchanged(any(), any(), any(), any()))
        .thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
    executor.shutdownNow();
  }

  @Test
  @DisplayName("비로그인 홈 요청은 외부 신호를 조회하지 않고 빈 시드를 반환한다")
  void collect_whenAnonymousHome_returnsEmptySeedsWithoutCallingSignalClients() {
    assertThat(service().collect()).isEmpty();
    verify(orderSignalClient, never()).getPurchaseSignals(any());
    verify(wishlistSignalClient, never()).getWishlistProductIds(any());
    verify(seedWeightsCache, never()).find(any());
  }

  @Test
  @DisplayName("가중치 캐시 히트 시 외부 신호 API를 호출하지 않는다")
  void collect_whenWeightsCacheHits_skipsSignalClients() {
    UUID memberId = login();
    List<Seed> cached = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.full(cached, Instant.now())));

    assertThat(service().collect()).isEqualTo(cached);
    verify(orderSignalClient, never()).getPurchaseSignals(any());
    verify(wishlistSignalClient, never()).getWishlistProductIds(any());
  }

  @Test
  @DisplayName("가중치 캐시가 부분 저장분이어도 짧은 TTL 안에서는 그대로 사용한다")
  void collect_whenCachedEntryIsPartial_stillUsesItWithoutRefreshing() {
    UUID memberId = login();
    List<Seed> cached = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.partial(cached, Instant.now().minusSeconds(60))));

    assertThat(service().collect()).isEqualTo(cached);
    verify(orderSignalClient, never()).getPurchaseSignals(any());
  }

  @Test
  @DisplayName("구매와 찜 신호 조회가 모두 성공하면 완전 엔트리로 긴 TTL에 저장한다")
  void refreshWeightsCache_whenBothSignalsSucceed_savesCompleteEntryWithFullTtl() {
    UUID memberId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenReturn(List.of());
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of());

    var result = service().refreshWeightsCache(memberId);

    SeedWeights saved = captureSave(SeedWeightsCache.FULL_TTL);
    assertThat(saved.seeds()).isEqualTo(result);
    assertThat(saved.complete()).isTrue();
    assertThat(saved.collectedAt()).isAfter(Instant.now().minusSeconds(60));
    verify(seedWeightsCache, never()).find(memberId);
    assertThat(counter("full")).isEqualTo(1);
  }

  @Test
  @DisplayName("구매와 찜 신호를 병렬로 조회한다")
  void refreshWeightsCache_fetchesSignalsConcurrently() throws Exception {
    UUID memberId = UUID.randomUUID();
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    when(orderSignalClient.getPurchaseSignals(memberId))
        .thenAnswer(
            ignored -> {
              started.countDown();
              release.await(1, TimeUnit.SECONDS);
              return List.of();
            });
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenAnswer(
            ignored -> {
              started.countDown();
              release.await(1, TimeUnit.SECONDS);
              return List.of();
            });

    CompletableFuture<List<Seed>> result =
        CompletableFuture.supplyAsync(() -> service().refreshWeightsCache(memberId));

    assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    assertThat(result.get(1, TimeUnit.SECONDS)).isEmpty();
  }

  @Test
  @DisplayName("신호 조회가 부분 실패하면 불완전 엔트리로 짧은 TTL에 저장한다")
  void refreshWeightsCache_whenOneSignalFails_savesIncompleteEntryWithPartialTtl() {
    UUID memberId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of());
    when(seedWeightsCache.find(memberId)).thenReturn(Optional.empty());

    var result = service().refreshWeightsCache(memberId);

    SeedWeights saved = captureSave(PARTIAL_TTL);
    assertThat(saved.seeds()).isEqualTo(result);
    assertThat(saved.complete()).isFalse();
    assertThat(saved.collectedAt()).isAfter(Instant.now().minusSeconds(60));
    assertThat(salvageCounter("none")).isEqualTo(1);
  }

  @Test
  @DisplayName("구매 신호만 실패하면 기존 캐시의 구매 시드를 살리고 새 찜 신호를 반영한다")
  void refreshWeightsCache_whenOrderFailsAndCacheExists_mergesFreshWishlistWithCachedPurchase() {
    UUID memberId = UUID.randomUUID();
    UUID cachedPurchaseId = UUID.randomUUID();
    UUID staleWishlistId = UUID.randomUUID();
    UUID freshWishlistId = UUID.randomUUID();
    Instant cachedAt = Instant.now().minus(Duration.ofHours(1));
    List<Seed> cached =
        List.of(new Seed(cachedPurchaseId, 0.5, true), new Seed(staleWishlistId, 0.3, false));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(freshWishlistId));
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.full(cached, cachedAt)));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result)
        .extracting(Seed::productId)
        .containsExactly(cachedPurchaseId, freshWishlistId);
    SeedWeights saved = captureSave(PARTIAL_TTL);
    assertThat(saved.seeds()).isEqualTo(result);
    assertThat(saved.complete()).isFalse();
    assertThat(saved.collectedAt()).isEqualTo(cachedAt);
    assertThat(counter("order-missing")).isEqualTo(1);
    assertThat(salvageCounter("merged")).isEqualTo(1);
  }

  @Test
  @DisplayName("찜 신호만 실패하면 기존 캐시의 찜 시드를 살리고 새 구매 신호를 반영한다")
  void refreshWeightsCache_whenWishlistFailsAndCacheExists_mergesFreshPurchaseWithCachedWishlist() {
    UUID memberId = UUID.randomUUID();
    UUID stalePurchaseId = UUID.randomUUID();
    UUID cachedWishlistId = UUID.randomUUID();
    UUID freshPurchaseId = UUID.randomUUID();
    List<Seed> cached =
        List.of(new Seed(stalePurchaseId, 0.5, true), new Seed(cachedWishlistId, 0.3, false));
    when(orderSignalClient.getPurchaseSignals(memberId))
        .thenReturn(List.of(new PurchaseSignal(freshPurchaseId, 1, 1, Instant.EPOCH)));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenThrow(new RuntimeException("member"));
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.full(cached, Instant.now().minusSeconds(30))));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result)
        .extracting(Seed::productId)
        .containsExactly(freshPurchaseId, cachedWishlistId);
    assertThat(captureSave(PARTIAL_TTL).seeds()).isEqualTo(result);
    assertThat(counter("wishlist-missing")).isEqualTo(1);
  }

  @Test
  @DisplayName("부분 실패 병합에서 같은 상품은 구매 시드를 남긴다")
  void refreshWeightsCache_whenPartialMergeHasDuplicate_keepsPurchaseSeed() {
    UUID memberId = UUID.randomUUID();
    UUID sharedId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId))
        .thenReturn(List.of(new PurchaseSignal(sharedId, 1, 1, Instant.EPOCH)));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenThrow(new RuntimeException("member"));
    when(seedWeightsCache.find(memberId))
        .thenReturn(
            Optional.of(
                SeedWeights.full(
                    List.of(new Seed(sharedId, 0.3, false)), Instant.now().minusSeconds(30))));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).containsExactly(new Seed(sharedId, 0.5, true));
  }

  @Test
  @DisplayName("부분 저장분에서 다시 살릴 때도 원래 수집 시각을 물려줘 수명이 늘어나지 않는다")
  void refreshWeightsCache_whenSalvagingFromPartialEntry_keepsOriginalCollectedAt() {
    UUID memberId = UUID.randomUUID();
    UUID cachedPurchaseId = UUID.randomUUID();
    UUID freshWishlistId = UUID.randomUUID();
    Instant originallyCollectedAt = Instant.now().minus(Duration.ofHours(20));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(freshWishlistId));
    when(seedWeightsCache.find(memberId))
        .thenReturn(
            Optional.of(
                SeedWeights.partial(
                    List.of(new Seed(cachedPurchaseId, 0.5, true)), originallyCollectedAt)));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).extracting(Seed::productId).contains(cachedPurchaseId);
    assertThat(captureSave(PARTIAL_TTL).collectedAt()).isEqualTo(originallyCollectedAt);
  }

  @Test
  @DisplayName("살릴 시드가 연령 상한을 넘으면 버리고 방금 받은 쪽만 남긴다")
  void refreshWeightsCache_whenSalvageAgeExceedsLimit_dropsFailedSideSeeds() {
    UUID memberId = UUID.randomUUID();
    UUID cachedPurchaseId = UUID.randomUUID();
    UUID freshWishlistId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(freshWishlistId));
    when(seedWeightsCache.find(memberId))
        .thenReturn(
            Optional.of(
                SeedWeights.partial(
                    List.of(new Seed(cachedPurchaseId, 0.5, true)),
                    Instant.now().minus(SALVAGE_MAX_AGE).minusSeconds(60))));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).extracting(Seed::productId).containsExactly(freshWishlistId);
    SeedWeights saved = captureSave(PARTIAL_TTL);
    assertThat(saved.seeds()).isEqualTo(result);
    assertThat(saved.collectedAt()).isAfter(Instant.now().minusSeconds(60));
    assertThat(salvageCounter("expired")).isEqualTo(1);
  }

  @Test
  @DisplayName("수집 시각을 알 수 없는 구버전 엔트리에서는 시드를 살리지 않는다")
  void refreshWeightsCache_whenCachedEntryHasUnknownOrigin_doesNotSalvage() {
    UUID memberId = UUID.randomUUID();
    UUID freshWishlistId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(freshWishlistId));
    when(seedWeightsCache.find(memberId))
        .thenReturn(
            Optional.of(
                SeedWeights.unknownOrigin(List.of(new Seed(UUID.randomUUID(), 0.5, true)))));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).extracting(Seed::productId).containsExactly(freshWishlistId);
    assertThat(salvageCounter("expired")).isEqualTo(1);
  }

  @Test
  @DisplayName("신호를 받는 동안 다른 스레드가 완전 데이터를 저장했으면 부분 저장으로 덮지 않는다")
  void refreshWeightsCache_whenCompleteEntryWasSavedConcurrently_skipsPartialWrite() {
    UUID memberId = UUID.randomUUID();
    List<Seed> concurrentlySaved = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenReturn(List.of(UUID.randomUUID()));
    when(seedWeightsCache.findSnapshot(memberId)).thenReturn(Optional.empty());
    when(seedWeightsCache.saveIfUnchanged(eq(memberId), any(), any(), eq(PARTIAL_TTL)))
        .thenReturn(false);
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.full(concurrentlySaved, Instant.now())));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).isEqualTo(concurrentlySaved);
    verify(seedWeightsCache, never()).save(eq(memberId), any(), any());
    assertThat(salvageCounter("superseded")).isEqualTo(1);
  }

  @Test
  @DisplayName("스냅샷 직전에 들어온 완전 엔트리는 부분 저장보다 우선한다")
  void refreshWeightsCache_whenSnapshotAlreadyContainsNewerCompleteEntry_keepsIt() {
    UUID memberId = UUID.randomUUID();
    List<Seed> completeSeeds = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(UUID.randomUUID()));
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.full(completeSeeds, Instant.now().plusSeconds(1))));

    assertThat(service().refreshWeightsCache(memberId)).isEqualTo(completeSeeds);

    verify(seedWeightsCache, never()).saveIfUnchanged(any(), any(), any(), any());
    assertThat(salvageCounter("superseded")).isEqualTo(1);
    assertThat(salvageCounter("none")).isZero();
    assertThat(salvageCounter("merged")).isZero();
    assertThat(salvageCounter("expired")).isZero();
  }

  @Test
  @DisplayName("구매와 찜 신호 조회가 모두 실패해도 기존 캐시가 있으면 덮어쓰지 않는다")
  void refreshWeightsCache_whenBothSignalsFailAndCacheExists_returnsExistingCache() {
    UUID memberId = UUID.randomUUID();
    List<Seed> cached = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenThrow(new RuntimeException("member"));
    when(seedWeightsCache.find(memberId))
        .thenReturn(Optional.of(SeedWeights.full(cached, Instant.now().minusSeconds(30))));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).isEqualTo(cached);
    verify(seedWeightsCache, never()).save(eq(memberId), any(), any());
    assertThat(counter("both-missing")).isEqualTo(1);
  }

  @Test
  @DisplayName("구매 신호 조회만 실패하면 찜 신호로 계속 추천 시드를 만든다")
  void collect_whenOnlyOrderFails_usesWishlistSignals() {
    UUID memberId = login();
    UUID wishlistId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(wishlistId));

    var result = service().collect();

    assertThat(result).extracting(Seed::productId).containsExactly(wishlistId);
    assertThat(captureSave(PARTIAL_TTL).seeds()).isEqualTo(result);
  }

  @Test
  @DisplayName("찜 신호 조회만 실패하면 구매 신호로 계속 추천 시드를 만든다")
  void collect_whenOnlyMemberFails_usesPurchaseSignals() {
    UUID memberId = login();
    UUID purchaseId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId))
        .thenReturn(List.of(new PurchaseSignal(purchaseId, 1, 1, Instant.EPOCH)));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenThrow(new RuntimeException("member"));

    var result = service().collect();

    assertThat(result).extracting(Seed::productId).containsExactly(purchaseId);
    assertThat(captureSave(PARTIAL_TTL).seeds()).isEqualTo(result);
  }

  @Test
  @DisplayName("구매와 찜 신호 조회가 모두 실패하면 빈 시드를 반환하고 캐시에 저장하지 않는다")
  void collect_whenBothSignalsFail_returnsEmptySeedsWithoutPersistingCache() {
    UUID memberId = login();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenThrow(new RuntimeException("member"));
    when(seedWeightsCache.find(memberId)).thenReturn(Optional.empty());

    assertThat(service().collect()).isEmpty();
    verify(seedWeightsCache, never()).save(any(), any(), any());
  }

  private RecommendationSeedService service() {
    return new RecommendationSeedService(
        orderSignalClient,
        wishlistSignalClient,
        seedScorer,
        seedWeightsCache,
        PARTIAL_TTL,
        SALVAGE_MAX_AGE,
        executor,
        new RecommendationMetrics(meterRegistry));
  }

  private SeedWeights captureSave(Duration expectedTtl) {
    ArgumentCaptor<SeedWeights> captor = ArgumentCaptor.forClass(SeedWeights.class);
    if (expectedTtl.equals(SeedWeightsCache.FULL_TTL)) {
      verify(seedWeightsCache).save(any(), captor.capture(), eq(expectedTtl));
    } else {
      verify(seedWeightsCache).saveIfUnchanged(any(), any(), captor.capture(), eq(expectedTtl));
    }
    return captor.getValue();
  }

  private double counter(String outcome) {
    return meterRegistry.counter("recommendation.seed-refresh", "outcome", outcome).count();
  }

  private double salvageCounter(String outcome) {
    return meterRegistry.counter("recommendation.seed-salvage", "outcome", outcome).count();
  }

  private UUID login() {
    UUID memberId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    return memberId;
  }
}

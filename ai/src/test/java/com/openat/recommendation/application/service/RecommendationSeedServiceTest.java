package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendationSeedServiceTest {

  private static final Duration PARTIAL_TTL = Duration.ofMinutes(10);

  @Mock private OrderSignalClient orderSignalClient;
  @Mock private WishlistSignalClient wishlistSignalClient;
  @Mock private SeedWeightsCache seedWeightsCache;
  private final SeedScorer seedScorer = new SeedScorer(0.3, 0.5, 0.1, 0.85, 20, 20);
  private final ExecutorService executor = Executors.newFixedThreadPool(4);

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
    executor.shutdownNow();
  }

  @Test
  @DisplayName("비로그인 홈 요청은 외부 신호를 조회하지 않고 빈 시드를 반환한다")
  void collect_whenAnonymousHome_returnsEmptySeedsWithoutCallingSignalClients() {
    assertThat(service().collect()).isEmpty();
    verify(orderSignalClient, never()).getPurchaseSignals(org.mockito.ArgumentMatchers.any());
    verify(wishlistSignalClient, never()).getWishlistProductIds(org.mockito.ArgumentMatchers.any());
    verify(seedWeightsCache, never()).find(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("가중치 캐시 히트 시 외부 신호 API를 호출하지 않는다")
  void collect_whenWeightsCacheHits_skipsSignalClients() {
    UUID memberId = login();
    UUID cachedProductId = UUID.randomUUID();
    List<Seed> cached = List.of(new Seed(cachedProductId, 0.5, true));
    when(seedWeightsCache.find(memberId)).thenReturn(java.util.Optional.of(cached));

    assertThat(service().collect()).isEqualTo(cached);
    verify(orderSignalClient, never()).getPurchaseSignals(org.mockito.ArgumentMatchers.any());
    verify(wishlistSignalClient, never()).getWishlistProductIds(org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("구매와 찜 신호 조회가 모두 성공하면 긴 TTL로 가중치 캐시에 저장한다")
  void refreshWeightsCache_whenBothSignalsSucceed_savesWithFullTtl() {
    UUID memberId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenReturn(List.of());
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of());

    var result = service().refreshWeightsCache(memberId);

    verify(seedWeightsCache).save(memberId, result, SeedWeightsCache.FULL_TTL);
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
  @DisplayName("신호 조회가 부분 실패하면 짧은 TTL로 가중치 캐시에 저장한다")
  void refreshWeightsCache_whenOneSignalFails_savesWithPartialTtl() {
    UUID memberId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of());
    when(seedWeightsCache.find(memberId)).thenReturn(Optional.empty());

    var result = service().refreshWeightsCache(memberId);

    verify(seedWeightsCache).save(memberId, result, PARTIAL_TTL);
  }

  @Test
  @DisplayName("신호 조회가 부분 실패해도 기존 캐시가 있으면 덮어쓰지 않는다")
  void refreshWeightsCache_whenOneSignalFailsAndCacheExists_returnsExistingCache() {
    UUID memberId = UUID.randomUUID();
    List<Seed> cached = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenReturn(List.of(UUID.randomUUID()));
    when(seedWeightsCache.find(memberId)).thenReturn(Optional.of(cached));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).isSameAs(cached);
    verify(seedWeightsCache, never()).save(eq(memberId), any(), any());
  }

  @Test
  @DisplayName("구매와 찜 신호 조회가 모두 실패해도 기존 캐시가 있으면 덮어쓰지 않는다")
  void refreshWeightsCache_whenBothSignalsFailAndCacheExists_returnsExistingCache() {
    UUID memberId = UUID.randomUUID();
    List<Seed> cached = List.of(new Seed(UUID.randomUUID(), 0.5, true));
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId))
        .thenThrow(new RuntimeException("member"));
    when(seedWeightsCache.find(memberId)).thenReturn(Optional.of(cached));

    var result = service().refreshWeightsCache(memberId);

    assertThat(result).isSameAs(cached);
    verify(seedWeightsCache, never()).save(eq(memberId), any(), any());
  }

  @Test
  @DisplayName("구매 신호 조회만 실패하면 찜 신호로 계속 추천 시드를 만든다")
  void collect_whenOnlyOrderFails_usesWishlistSignals() {
    UUID memberId = login();
    UUID wishlistId = UUID.randomUUID();
    when(orderSignalClient.getPurchaseSignals(memberId)).thenThrow(new RuntimeException("order"));
    when(wishlistSignalClient.getWishlistProductIds(memberId)).thenReturn(List.of(wishlistId));

    var result = service().collect();

    assertThat(result).extracting(seed -> seed.productId()).containsExactly(wishlistId);
    verify(seedWeightsCache).save(memberId, result, PARTIAL_TTL);
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

    assertThat(result).extracting(seed -> seed.productId()).containsExactly(purchaseId);
    verify(seedWeightsCache).save(memberId, result, PARTIAL_TTL);
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
    verify(seedWeightsCache, never())
        .save(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private RecommendationSeedService service() {
    return new RecommendationSeedService(
        orderSignalClient,
        wishlistSignalClient,
        seedScorer,
        seedWeightsCache,
        PARTIAL_TTL,
        executor);
  }

  private UUID login() {
    UUID memberId = UUID.randomUUID();
    UserContextHolder.set(new UserContext(memberId.toString(), Set.of("USER")));
    return memberId;
  }
}

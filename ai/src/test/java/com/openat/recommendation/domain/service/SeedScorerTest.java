package com.openat.recommendation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.recommendation.domain.model.PurchaseSignal;
import com.openat.recommendation.domain.model.Seed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeedScorerTest {

  private final SeedScorer scorer = scorer(0.3, 0.5, 0.1, 0.85, 20, 20);

  @Test
  @DisplayName("구매 점수는 주문 횟수가 늘 때마다 0.1씩 증가한다")
  void purchaseScore_whenOrderCountIncreases_increasesByPointOne() {
    assertThat(scorer.purchaseScore(1)).isEqualTo(0.5);
    assertThat(scorer.purchaseScore(2)).isEqualTo(0.6);
    assertThat(scorer.purchaseScore(4)).isEqualTo(0.8);
  }

  @Test
  @DisplayName("구매 점수는 주문 횟수가 많아도 0.85를 넘지 않는다")
  void purchaseScore_whenOrderCountIsHigh_capsAtPointEightFive() {
    assertThat(scorer.purchaseScore(5)).isEqualTo(0.85);
    assertThat(scorer.purchaseScore(100)).isEqualTo(0.85);
  }

  @Test
  @DisplayName("구매와 찜 신호는 각각 최신 20개까지만 시드로 만든다")
  void scoreSignals_whenSignalsExceedLimits_truncatesEachTypeToTwenty() {
    List<PurchaseSignal> purchases =
        IntStream.range(0, 25).mapToObj(index -> purchase(UUID.randomUUID(), 1)).toList();
    List<UUID> wishlist = IntStream.range(0, 25).mapToObj(index -> UUID.randomUUID()).toList();

    var result = scorer.scoreSignals(purchases, wishlist);

    assertThat(result).hasSize(40);
    assertThat(result).filteredOn(seed -> seed.buy()).hasSize(20);
    assertThat(result).filteredOn(seed -> !seed.buy()).hasSize(20);
  }

  @Test
  @DisplayName("같은 상품이 구매와 찜에 있으면 더 강한 구매 시드 하나만 유지한다")
  void scoreSignals_whenPurchaseAndWishlistOverlap_keepsPurchaseSeed() {
    UUID productId = UUID.randomUUID();

    var result = scorer.scoreSignals(List.of(purchase(productId, 2)), List.of(productId));

    assertThat(result)
        .singleElement()
        .satisfies(
            seed -> {
              assertThat(seed.productId()).isEqualTo(productId);
              assertThat(seed.score()).isEqualTo(0.6);
              assertThat(seed.buy()).isTrue();
            });
  }

  @Test
  @DisplayName("찜 점수가 더 높게 설정되어도 중복 상품은 구매 신호를 유지한다")
  void scoreSignals_whenWishlistWeightExceedsPurchaseWeight_stillKeepsPurchaseSeed() {
    SeedScorer retunedScorer = scorer(0.8, 0.5, 0.1, 0.85, 20, 20);
    UUID productId = UUID.randomUUID();

    var result = retunedScorer.scoreSignals(List.of(purchase(productId, 1)), List.of(productId));

    assertThat(result)
        .singleElement()
        .satisfies(
            seed -> {
              assertThat(seed.score()).isEqualTo(0.5);
              assertThat(seed.buy()).isTrue();
            });
  }

  @Test
  @DisplayName("상세 요청은 현재 상품 시드 하나만 만든다")
  void currentProductSeed_returnsOnlyCurrentProductSeed() {
    UUID productId = UUID.randomUUID();

    var result = scorer.currentProductSeed(productId);

    assertThat(result)
        .singleElement()
        .satisfies(
            seed -> {
              assertThat(seed.productId()).isEqualTo(productId);
              assertThat(seed.score()).isEqualTo(0.9);
              assertThat(seed.buy()).isFalse();
            });
  }

  private PurchaseSignal purchase(UUID productId, long orderCount) {
    return new PurchaseSignal(productId, orderCount, orderCount, Instant.EPOCH);
  }

  private SeedScorer scorer(
      double wishlistScore,
      double purchaseBaseScore,
      double purchaseIncrement,
      double purchaseMaxScore,
      int purchaseLimit,
      int wishlistLimit) {
    return new SeedScorer(
        wishlistScore,
        purchaseBaseScore,
        purchaseIncrement,
        purchaseMaxScore,
        purchaseLimit,
        wishlistLimit);
  }
}

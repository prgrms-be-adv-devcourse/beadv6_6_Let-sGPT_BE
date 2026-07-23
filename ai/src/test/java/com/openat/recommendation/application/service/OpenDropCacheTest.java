package com.openat.recommendation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.openat.recommendation.application.port.out.OpenDropClient;
import com.openat.recommendation.domain.model.DropMeta;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OpenDropCacheTest {

  @Mock private OpenDropClient openDropClient;

  private OpenDropCache cache;

  @BeforeEach
  void setUp() {
    cache = new OpenDropCache(openDropClient);
  }

  @Test
  @DisplayName("갱신한 캐시로 후보를 필터링하고 단건 메타를 조회한다")
  void refresh_updatesFilterAndLookup() {
    UUID openProductId = UUID.randomUUID();
    UUID closedProductId = UUID.randomUUID();
    DropMeta meta = drop(openProductId, UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(meta));

    cache.refresh();

    assertThat(cache.filterOpenProductIds(List.of(closedProductId, openProductId)))
        .containsExactly(openProductId);
    assertThat(cache.findByProductId(openProductId)).contains(meta);
    assertThat(cache.findByProductId(closedProductId)).isEmpty();
  }

  @Test
  @DisplayName("갱신 실패 시 이전 캐시를 유지한다")
  void refresh_whenClientFails_keepsPreviousCache() {
    UUID productId = UUID.randomUUID();
    DropMeta previous = drop(productId, UUID.randomUUID(), null);
    when(openDropClient.getAllOpenDrops())
        .thenReturn(List.of(previous))
        .thenThrow(new RuntimeException("product unavailable"));

    cache.refresh();
    cache.refresh();

    assertThat(cache.findByProductId(productId)).contains(previous);
  }

  @Test
  @DisplayName("성공한 갱신은 완성된 새 맵으로 기존 상태를 교체한다")
  void refresh_replacesWholeSnapshot() {
    UUID oldProductId = UUID.randomUUID();
    UUID newProductId = UUID.randomUUID();
    DropMeta oldMeta = drop(oldProductId, UUID.randomUUID(), null);
    DropMeta newMeta = drop(newProductId, UUID.randomUUID(), null);
    when(openDropClient.getAllOpenDrops())
        .thenReturn(List.of(oldMeta))
        .thenReturn(List.of(newMeta));

    cache.refresh();
    assertThat(cache.findByProductId(oldProductId)).contains(oldMeta);

    cache.refresh();

    assertThat(cache.findByProductId(oldProductId)).isEmpty();
    assertThat(cache.findByProductId(newProductId)).contains(newMeta);
  }

  @Test
  @DisplayName("같은 상품의 드롭은 마감 시각이 더 이른 것을 유지한다")
  void refresh_whenProductIdsDuplicate_keepsSoonerClosingDrop() {
    UUID productId = UUID.randomUUID();
    DropMeta later =
        drop(productId, UUID.randomUUID(), Instant.parse("2099-02-01T00:00:00Z"));
    DropMeta sooner =
        drop(productId, UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(later, sooner));

    cache.refresh();

    assertThat(cache.findByProductId(productId)).contains(sooner);
  }

  @Test
  @DisplayName("같은 상품의 드롭 마감 시각이 같으면 가격이 낮은 드롭을 유지한다")
  void refresh_whenDuplicateDropsCloseAtSameTime_keepsLowerPricedDrop() {
    UUID productId = UUID.randomUUID();
    Instant closeAt = Instant.parse("2099-01-01T00:00:00Z");
    DropMeta expensive = drop(UUID.randomUUID(), productId, UUID.randomUUID(), 2000L, closeAt);
    DropMeta cheap = drop(UUID.randomUUID(), productId, UUID.randomUUID(), 1000L, closeAt);
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(expensive, cheap));

    cache.refresh();

    assertThat(cache.findByProductId(productId)).contains(cheap);
  }

  @Test
  @DisplayName("같은 상품의 드롭 마감 시각과 가격이 같으면 드롭 ID가 작은 것을 유지한다")
  void refresh_whenDuplicateDropsCloseAtAndPriceSame_keepsLowerDropId() {
    UUID productId = UUID.randomUUID();
    UUID lowerDropId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID higherDropId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Instant closeAt = Instant.parse("2099-01-01T00:00:00Z");
    DropMeta higher = drop(higherDropId, productId, UUID.randomUUID(), 1000L, closeAt);
    DropMeta lower = drop(lowerDropId, productId, UUID.randomUUID(), 1000L, closeAt);
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(higher, lower));

    cache.refresh();

    assertThat(cache.findByProductId(productId)).contains(lower);
  }

  @Test
  @DisplayName("같은 상품의 여러 드롭은 우선순위가 가장 높은 대표 드롭 하나로 병합한다")
  void refresh_whenMultipleDropsShareProductId_mergesIntoOneRepresentative() {
    UUID productId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    UUID lowerDropId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID higherDropId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    Instant soonerCloseAt = Instant.parse("2099-01-01T00:00:00Z");
    DropMeta later =
        drop(UUID.randomUUID(), productId, categoryId, 500L, Instant.parse("2099-02-01T00:00:00Z"));
    DropMeta expensive = drop(UUID.randomUUID(), productId, categoryId, 2000L, soonerCloseAt);
    DropMeta higherId = drop(higherDropId, productId, categoryId, 1000L, soonerCloseAt);
    DropMeta representative = drop(lowerDropId, productId, categoryId, 1000L, soonerCloseAt);
    when(openDropClient.getAllOpenDrops())
        .thenReturn(List.of(later, expensive, higherId, representative));

    cache.refresh();

    assertThat(cache.findGeneral(10)).containsExactly(representative);
    assertThat(cache.findByProductId(productId)).contains(representative);
  }

  @Test
  @DisplayName("같은 상품의 드롭 중 마감 시각이 있는 드롭을 우선 유지한다")
  void refresh_whenDuplicateDropHasNoCloseAt_keepsDropWithCloseAt() {
    UUID productId = UUID.randomUUID();
    DropMeta noDeadline = drop(productId, UUID.randomUUID(), null);
    DropMeta withDeadline =
        drop(productId, UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(noDeadline, withDeadline));

    cache.refresh();

    assertThat(cache.findByProductId(productId)).contains(withDeadline);
  }

  @Test
  @DisplayName("카테고리가 같은 드롭만 캐시 순서대로 제한해 조회한다")
  void findByCategory_filtersAndLimitsDrops() {
    UUID categoryId = UUID.randomUUID();
    DropMeta first = drop(UUID.randomUUID(), categoryId, null);
    DropMeta other = drop(UUID.randomUUID(), UUID.randomUUID(), null);
    DropMeta second = drop(UUID.randomUUID(), categoryId, null);
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(first, other, second));
    cache.refresh();

    assertThat(cache.findByCategory(categoryId, 1)).containsExactly(first);
  }

  @Test
  @DisplayName("카테고리 조회에서 이미 마감된 드롭을 제외한다")
  void findByCategory_excludesClosedDrops() {
    UUID categoryId = UUID.randomUUID();
    DropMeta closed = drop(UUID.randomUUID(), categoryId, Instant.now().minusSeconds(1));
    DropMeta open = drop(UUID.randomUUID(), categoryId, Instant.now().plusSeconds(60));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(closed, open));
    cache.refresh();

    assertThat(cache.findByCategory(categoryId, 2)).containsExactly(open);
  }

  @Test
  @DisplayName("카테고리 드롭은 마감 임박순으로 정렬하고 마감 시각이 없으면 뒤에 둔다")
  void findByCategory_sortsByCloseAtWithNullLast() {
    UUID categoryId = UUID.randomUUID();
    DropMeta noDeadline = drop(UUID.randomUUID(), categoryId, null);
    DropMeta later =
        drop(UUID.randomUUID(), categoryId, Instant.parse("2099-02-01T00:00:00Z"));
    DropMeta sooner =
        drop(UUID.randomUUID(), categoryId, Instant.parse("2099-01-01T00:00:00Z"));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(noDeadline, later, sooner));
    cache.refresh();

    assertThat(cache.findByCategory(categoryId, 3)).containsExactly(sooner, later, noDeadline);
  }

  @Test
  @DisplayName("일반 드롭은 마감 임박순으로 정렬하고 마감 시각이 없으면 뒤에 둔다")
  void findGeneral_sortsByCloseAtWithNullLast() {
    DropMeta noDeadline = drop(UUID.randomUUID(), UUID.randomUUID(), null);
    DropMeta later =
        drop(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2099-02-01T00:00:00Z"));
    DropMeta sooner =
        drop(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2099-01-01T00:00:00Z"));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(noDeadline, later, sooner));
    cache.refresh();

    assertThat(cache.findGeneral(3)).containsExactly(sooner, later, noDeadline);
  }

  @Test
  @DisplayName("일반 조회에서 이미 마감된 드롭을 제외한다")
  void findGeneral_excludesClosedDrops() {
    DropMeta closed =
        drop(UUID.randomUUID(), UUID.randomUUID(), Instant.now().minusSeconds(1));
    DropMeta open = drop(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(closed, open));
    cache.refresh();

    assertThat(cache.findGeneral(2)).containsExactly(open);
  }

  @Test
  @DisplayName("갱신 주기 사이 마감된 드롭은 후보 필터에서 제외한다")
  void filterOpenProductIds_excludesDropClosedAfterRefresh() {
    UUID productId = UUID.randomUUID();
    DropMeta closed = drop(productId, UUID.randomUUID(), Instant.now().minusSeconds(1));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(closed));
    cache.refresh();

    assertThat(cache.filterOpenProductIds(List.of(productId))).isEmpty();
  }

  @Test
  @DisplayName("단건 조회에서 이미 마감된 드롭을 제외한다")
  void findByProductId_excludesClosedDrop() {
    UUID productId = UUID.randomUUID();
    DropMeta closed = drop(productId, UUID.randomUUID(), Instant.now().minusSeconds(1));
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(closed));
    cache.refresh();

    assertThat(cache.findByProductId(productId)).isEmpty();
  }

  @Test
  @DisplayName("빈 목록으로 갱신하면 기존 캐시를 비운다")
  void refresh_whenClientReturnsEmptyList_clearsPreviousCache() {
    UUID productId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();
    DropMeta previous = drop(productId, categoryId, null);
    when(openDropClient.getAllOpenDrops()).thenReturn(List.of(previous)).thenReturn(List.of());

    cache.refresh();
    cache.refresh();

    assertThat(cache.filterOpenProductIds(List.of(productId))).isEmpty();
    assertThat(cache.findByProductId(productId)).isEmpty();
    assertThat(cache.findByCategory(categoryId, 1)).isEmpty();
    assertThat(cache.findGeneral(1)).isEmpty();
  }

  @Test
  @DisplayName("갱신 전 빈 캐시의 조회 메서드는 빈 결과를 반환한다")
  void lookup_beforeRefresh_returnsEmptyResults() {
    UUID productId = UUID.randomUUID();
    UUID categoryId = UUID.randomUUID();

    assertThat(cache.filterOpenProductIds(List.of(productId))).isEmpty();
    assertThat(cache.findByProductId(productId)).isEmpty();
    assertThat(cache.findByCategory(categoryId, 1)).isEmpty();
    assertThat(cache.findGeneral(1)).isEmpty();
  }

  private DropMeta drop(UUID productId, UUID categoryId, Instant closeAt) {
    return drop(UUID.randomUUID(), productId, categoryId, 1000L, closeAt);
  }

  private DropMeta drop(
      UUID dropId, UUID productId, UUID categoryId, long dropPrice, Instant closeAt) {
    return new DropMeta(
        dropId,
        productId,
        "상품",
        "판매자",
        dropPrice,
        "thumb.png",
        categoryId,
        closeAt);
  }
}

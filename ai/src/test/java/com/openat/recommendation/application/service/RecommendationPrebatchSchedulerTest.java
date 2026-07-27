package com.openat.recommendation.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Timeout(10)
class RecommendationPrebatchSchedulerTest {

  @Mock OpenDropCache openDropCache;
  @Mock RecommendationService recommendationService;

  private RecommendationPrebatchScheduler scheduler(boolean enabled) {
    return new RecommendationPrebatchScheduler(openDropCache, recommendationService, enabled);
  }

  @Test
  void prebatch_warmsDetailForEachOpenDropProduct() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(openDropCache.openProductIds()).thenReturn(List.of(first, second));
    when(recommendationService.warmDetail(any())).thenReturn(true);

    scheduler(true).prebatch();

    verify(recommendationService).warmDetail(first);
    verify(recommendationService).warmDetail(second);
  }

  @Test
  void prebatch_skipsProductsAlreadyFreshInCache() {
    UUID fresh = UUID.randomUUID();
    UUID cold = UUID.randomUUID();
    when(openDropCache.openProductIds()).thenReturn(List.of(fresh, cold));
    // warmDetail은 신선한 상품엔 false(건너뜀)를 반환한다. 배치는 둘 다 호출하되 정상 완료해야 한다.
    when(recommendationService.warmDetail(fresh)).thenReturn(false);
    when(recommendationService.warmDetail(cold)).thenReturn(true);

    scheduler(true).prebatch();

    verify(recommendationService).warmDetail(fresh);
    verify(recommendationService).warmDetail(cold);
  }

  @Test
  void prebatch_whenOneProductFails_continuesWithTheRest() {
    UUID failing = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    when(openDropCache.openProductIds()).thenReturn(List.of(failing, second, third));
    when(recommendationService.warmDetail(failing)).thenThrow(new RuntimeException("boom"));
    when(recommendationService.warmDetail(second)).thenReturn(true);
    when(recommendationService.warmDetail(third)).thenReturn(true);

    scheduler(true).prebatch();

    // 한 상품 실패가 배치를 중단시키지 않는다 — 나머지가 모두 처리된다.
    verify(recommendationService).warmDetail(failing);
    verify(recommendationService).warmDetail(second);
    verify(recommendationService).warmDetail(third);
  }

  @Test
  void prebatch_whenDisabled_doesNoWork() {
    scheduler(false).prebatch();

    verifyNoInteractions(openDropCache);
    verify(recommendationService, never()).warmDetail(any());
  }

  @Test
  void prebatch_whenNoOpenDrops_doesNotWarm() {
    when(openDropCache.openProductIds()).thenReturn(List.of());

    scheduler(true).prebatch();

    verify(recommendationService, never()).warmDetail(any());
  }
}

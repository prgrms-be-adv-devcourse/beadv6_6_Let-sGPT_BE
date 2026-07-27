package com.openat.recommendation.application.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 열린 드롭 상품의 DETAIL 추천을 주기적으로 미리 계산해 캐시({@code rec:detail:{productId}})를
 * 데워 두는 프리배치. 첫 방문자가 콜드 미스로 LLM 지연을 겪지 않도록 한다.
 *
 * <p>새벽 고정이 아니라 설정 가능한 간격으로 돈다. LLM 비용이 있으므로 코드 기본값은 비활성
 * ({@code recommendation.prebatch.enabled=false})이며 환경 변수로 켤 수 있다.
 *
 * <p>홈은 회원별이라 프리배치 대상이 아니다. 상세만 공유 캐시 키를 쓰므로 데울 수 있다.
 *
 * <p>비용 억제: 이미 신선한 상품은 건너뛰고(콜드만 데움), {@link RecommendationService#warmDetail}
 * 이 라이브 요청과 같은 single-flight·pipelineLimiter 경로를 재사용해 중복 계산·다운스트림
 * 과부하를 막는다. 상품은 순차 처리하며 한 상품이 실패해도 로그만 남기고 나머지를 계속한다.
 */
@Component
public class RecommendationPrebatchScheduler {

  private static final Logger log = LoggerFactory.getLogger(RecommendationPrebatchScheduler.class);

  private final OpenDropCache openDropCache;
  private final RecommendationService recommendationService;
  private final boolean enabled;

  public RecommendationPrebatchScheduler(
      OpenDropCache openDropCache,
      RecommendationService recommendationService,
      @Value("${recommendation.prebatch.enabled:false}") boolean enabled) {
    this.openDropCache = openDropCache;
    this.recommendationService = recommendationService;
    this.enabled = enabled;
  }

  @Scheduled(
      scheduler = "recommendationTaskScheduler",
      fixedDelayString = "${recommendation.prebatch.interval:6h}",
      initialDelayString = "${recommendation.prebatch.initial-delay:1m}")
  public void prebatch() {
    if (!enabled) {
      return;
    }
    List<UUID> productIds = openDropCache.openProductIds();
    if (productIds.isEmpty()) {
      log.info("recommendation prebatch skipped: no open drops");
      return;
    }
    int warmed = 0;
    int skipped = 0;
    int failed = 0;
    for (UUID productId : productIds) {
      try {
        if (recommendationService.warmDetail(productId)) {
          warmed++;
        } else {
          skipped++;
        }
      } catch (RuntimeException | Error throwable) {
        // 한 상품의 실패가 배치 전체를 멈추지 않는다. 로그만 남기고 다음 상품으로 넘어간다.
        failed++;
        log.warn("recommendation prebatch failed for product: productId={}", productId, throwable);
      }
    }
    log.info(
        "recommendation prebatch done: candidates={}, warmed={}, skipped={}, failed={}",
        productIds.size(),
        warmed,
        skipped,
        failed);
  }
}

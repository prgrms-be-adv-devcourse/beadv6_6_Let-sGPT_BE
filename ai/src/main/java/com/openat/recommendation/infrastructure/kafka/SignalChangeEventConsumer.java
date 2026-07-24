package com.openat.recommendation.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.recommendation.application.service.RecommendationSeedService;
import com.openat.recommendation.infrastructure.cache.RecommendationResultCache;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class SignalChangeEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(SignalChangeEventConsumer.class);

  private final ObjectMapper objectMapper;
  private final RecommendationSeedService seedService;
  private final RecommendationResultCache resultCache;

  public SignalChangeEventConsumer(
      ObjectMapper objectMapper,
      RecommendationSeedService seedService,
      RecommendationResultCache resultCache) {
    this.objectMapper = objectMapper;
    this.seedService = seedService;
    this.resultCache = resultCache;
  }

  @KafkaListener(
      topics = "${recommendation.kafka.topic.order-completed}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void onOrderCompleted(ConsumerRecord<String, String> record) {
    try {
      OrderCompletedEvent event = objectMapper.readValue(record.value(), OrderCompletedEvent.class);
      refresh(event.memberId());
    } catch (Exception exception) {
      log.error(
          "Failed to consume order completed event; skipping: payload={}",
          record.value(),
          exception);
    }
  }

  @KafkaListener(
      topics = "${recommendation.kafka.topic.wishlist-changed}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void onWishlistChanged(ConsumerRecord<String, String> record) {
    try {
      WishlistChangedEvent event =
          objectMapper.readValue(record.value(), WishlistChangedEvent.class);
      refresh(event.userId());
    } catch (Exception exception) {
      log.error(
          "Failed to consume wishlist changed event; skipping: payload={}",
          record.value(),
          exception);
    }
  }

  private void refresh(UUID memberId) {
    if (memberId == null) {
      log.warn("Signal change event missing memberId; skipping");
      return;
    }
    resultCache.invalidateMember(memberId);
    seedService.refreshWeightsCache(memberId);
    // 무효화와 재계산 사이 유입 요청이 낡은 값을 재적재하는 창을 차단한다.
    resultCache.invalidateMember(memberId);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OrderCompletedEvent(UUID memberId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record WishlistChangedEvent(UUID userId) {}
}

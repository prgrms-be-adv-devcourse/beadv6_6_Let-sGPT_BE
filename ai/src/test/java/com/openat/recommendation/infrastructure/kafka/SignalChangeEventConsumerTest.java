package com.openat.recommendation.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openat.recommendation.application.service.RecommendationSeedService;
import com.openat.recommendation.infrastructure.cache.RecommendationResultCache;
import com.openat.recommendation.infrastructure.config.JacksonConfig;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignalChangeEventConsumerTest {

  @Mock RecommendationSeedService seedService;
  @Mock RecommendationResultCache resultCache;

  @Test
  void orderCompleted_usesMemberIdToRefreshAndInvalidate() {
    UUID memberId = UUID.randomUUID();
    consumer().onOrderCompleted(record(orderPayload(memberId)));

    InOrder inOrder = inOrder(resultCache, seedService);
    inOrder.verify(resultCache).invalidateMember(memberId);
    inOrder.verify(seedService).refreshWeightsCache(memberId);
    inOrder.verify(resultCache).invalidateMember(memberId);
  }

  @Test
  void wishlistChanged_usesUserIdToRefreshAndInvalidate() {
    UUID userId = UUID.randomUUID();
    String payload =
        "{\"userId\":\"%s\",\"productId\":\"%s\",\"type\":\"CREATE\",\"occurredAt\":\"%s\"}"
            .formatted(userId, UUID.randomUUID(), Instant.EPOCH);

    consumer().onWishlistChanged(record(payload));

    InOrder inOrder = inOrder(resultCache, seedService);
    inOrder.verify(resultCache).invalidateMember(userId);
    inOrder.verify(seedService).refreshWeightsCache(userId);
    inOrder.verify(resultCache).invalidateMember(userId);
  }

  @Test
  void handler_whenRefreshFails_doesNotRethrow() {
    UUID memberId = UUID.randomUUID();
    doThrow(new RuntimeException("refresh")).when(seedService).refreshWeightsCache(memberId);

    assertThatCode(() -> consumer().onOrderCompleted(record(orderPayload(memberId))))
        .doesNotThrowAnyException();
    verify(resultCache).invalidateMember(memberId);
  }

  @Test
  void orderCompleted_whenPayloadIsMalformed_doesNotRethrow() {
    assertThatCode(() -> consumer().onOrderCompleted(record("not-json")))
        .doesNotThrowAnyException();
    verify(seedService, never()).refreshWeightsCache(any());
    verify(resultCache, never()).invalidateMember(any());
  }

  @Test
  void wishlistChanged_whenPayloadIsMalformed_doesNotRethrow() {
    assertThatCode(() -> consumer().onWishlistChanged(record("not-json")))
        .doesNotThrowAnyException();
    verify(seedService, never()).refreshWeightsCache(any());
    verify(resultCache, never()).invalidateMember(any());
  }

  @Test
  void wishlistChanged_whenUserIdIsMissing_skipsRefreshWithoutError() {
    String payload =
        "{\"productId\":\"%s\",\"type\":\"CREATE\",\"occurredAt\":\"%s\"}"
            .formatted(UUID.randomUUID(), Instant.EPOCH);

    assertThatCode(() -> consumer().onWishlistChanged(record(payload))).doesNotThrowAnyException();
    verify(seedService, never()).refreshWeightsCache(any());
    verify(resultCache, never()).invalidateMember(any());
  }

  private SignalChangeEventConsumer consumer() {
    return new SignalChangeEventConsumer(
        new JacksonConfig().objectMapper(), seedService, resultCache);
  }

  private ConsumerRecord<String, String> record(String payload) {
    return new ConsumerRecord<>("topic", 0, 0, null, payload);
  }

  private String orderPayload(UUID memberId) {
    return "{\"orderId\":\"%s\",\"sellerId\":\"%s\",\"productId\":\"%s\",\"memberId\":\"%s\",\"amount\":100}"
        .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), memberId);
  }
}

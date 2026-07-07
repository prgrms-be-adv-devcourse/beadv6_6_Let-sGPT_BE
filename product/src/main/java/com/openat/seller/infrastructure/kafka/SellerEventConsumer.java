package com.openat.seller.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.seller.application.usecase.SellerStoreCommandUseCase;
import com.openat.seller.infrastructure.kafka.event.SellerStoreChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SellerEventConsumer {

  private final ObjectMapper objectMapper;
  private final SellerStoreCommandUseCase sellerStoreCommandUseCase;

  @KafkaListener(
      topics = {
        "${product.kafka.topic.seller-registered}",
        "${product.kafka.topic.seller-updated}"
      },
      groupId = "${spring.kafka.consumer.group-id}")
  public void onSellerStoreChanged(ConsumerRecord<String, String> record) {
    String payload = record.value();
    try {
      SellerStoreChangedEvent event =
          objectMapper.readValue(payload, SellerStoreChangedEvent.class);
      sellerStoreCommandUseCase.upsert(event.sellerInfoId(), event.storeName());
      log.info("seller store projection updated. sellerInfoId={}", event.sellerInfoId());
    } catch (Exception e) {
      log.error("seller store event consume failed. payload={}", payload, e);
      throw new RuntimeException("seller store event consume failed", e);
    }
  }
}

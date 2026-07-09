package com.openat.search.product.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.product.presentation.dto.TopicProduceTestResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTopicProduceTestService {

  private static final int SAMPLE_COUNT = 20;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  @Value("${search.kafka.topic.product-created}")
  private String productCreatedTopic;

  @Value("${search.kafka.topic.product-updated}")
  private String productUpdatedTopic;

  @Value("${search.kafka.topic.product-deleted}")
  private String productDeletedTopic;

  public TopicProduceTestResponse produceSamples() {
    for (int index = 1; index <= SAMPLE_COUNT; index++) {
      Map<String, Object> basePayload = samplePayload(index);
      String key = String.valueOf(basePayload.get("id"));

      publish(productCreatedTopic, key, basePayload, "product-created");
      publish(productUpdatedTopic, key, updatedPayload(basePayload), "product-updated");
      publish(productDeletedTopic, key, deletedPayload(basePayload), "product-deleted");
    }

    int totalCount = SAMPLE_COUNT * 3;
    return new TopicProduceTestResponse(
        "Search product Kafka topic produce test requested.",
        productCreatedTopic,
        productUpdatedTopic,
        productDeletedTopic,
        SAMPLE_COUNT,
        SAMPLE_COUNT,
        SAMPLE_COUNT,
        totalCount);
  }

  private Map<String, Object> samplePayload(int index) {
    UUID id =
        UUID.nameUUIDFromBytes(
            ("search-product-topic-produce-test-" + index).getBytes(StandardCharsets.UTF_8));
    Instant createdAt = Instant.parse("2026-07-08T00:00:00Z").plusSeconds(index * 60L);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id.toString());
    payload.put("created_at", createdAt.toString());
    payload.put("name", "Kafka 샘플 상품 " + index);
    payload.put("description", "search 모듈 Kafka producer 테스트용 상품 payload " + index);
    payload.put("price", 10000L + (index * 1000L));
    payload.put("thumbnail_key", "sample/product-" + index + ".jpg");
    payload.put("updated_at", createdAt.toString());
    payload.put("category_nm", sampleCategoryName(index));
    return payload;
  }

  private Map<String, Object> updatedPayload(Map<String, Object> basePayload) {
    Map<String, Object> payload = new LinkedHashMap<>(basePayload);
    payload.put("name", basePayload.get("name") + " 수정");
    payload.put("description", basePayload.get("description") + " - 수정 이벤트");
    payload.put("price", ((Long) basePayload.get("price")) + 500L);
    payload.put(
        "updated_at",
        Instant.parse((String) basePayload.get("updated_at")).plusSeconds(3600).toString());
    return payload;
  }

  private Map<String, Object> deletedPayload(Map<String, Object> basePayload) {
    Map<String, Object> payload = new LinkedHashMap<>(basePayload);
    payload.put("description", basePayload.get("description") + " - 삭제 이벤트");
    payload.put(
        "updated_at",
        Instant.parse((String) basePayload.get("updated_at")).plusSeconds(7200).toString());
    return payload;
  }

  private String sampleCategoryName(int index) {
    return switch (index % 5) {
      case 1 -> "가방";
      case 2 -> "의류";
      case 3 -> "전자기기";
      case 4 -> "문구";
      default -> "기타";
    };
  }

  private void publish(String topic, String key, Map<String, Object> payload, String eventType) {
    String message = toJson(payload);
    kafkaTemplate
        .send(topic, key, message)
        .whenComplete(
            (result, exception) -> {
              if (exception != null) {
                log.error(
                    "Search product Kafka sample publish failed. eventType={}, topic={}, key={}, payload={}",
                    eventType,
                    topic,
                    key,
                    message,
                    exception);
                return;
              }

              var metadata = result.getRecordMetadata();
              log.info(
                  "Search product Kafka sample published. eventType={}, topic={}, partition={}, offset={}, key={}, payload={}",
                  eventType,
                  metadata.topic(),
                  metadata.partition(),
                  metadata.offset(),
                  key,
                  message);
            });
  }

  private String toJson(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "Search product Kafka sample payload JSON conversion failed.", e);
    }
  }
}

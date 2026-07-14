package com.openat.search.product.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.product.application.dto.ProductSearchSyncTestResponse;
import com.openat.search.product.application.dto.ProductSearchSyncTestResponse.ProductSyncOperation;
import com.openat.search.product.presentation.dto.TopicProduceTestResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
  private static final int SYNC_INSERT_COUNT = 40;
  private static final int SYNC_UPDATE_COUNT = 10;
  private static final int SYNC_DELETE_COUNT = 10;
  private static final Duration EVENT_TIME_GAP = Duration.ofMinutes(1);
  private static final List<String> SAMPLE_THUMBNAIL_URLS =
      List.of(
          "http://localhost:9110/api/v1/products/images/f392928b-6cb3-4679-aa5b-ca28989b04fe.jpg",
          "http://localhost:9110/api/v1/products/images/710f747a-ceb2-42f5-a9fc-dcb0700a5d71.jpg",
          "http://localhost:9110/api/v1/products/images/3a483fb2-0d56-445c-883f-5fba3184f288.jpg",
          "http://localhost:9110/api/v1/products/images/9bd4ad9e-83c5-43f9-9d1d-31962bdd5c24.jpg",
          "http://localhost:9110/api/v1/products/images/7ae16ecf-45d1-4142-b43e-e2abeaa003e3.jpg",
          "http://localhost:9110/api/v1/products/images/48231b3a-babf-46fc-9d2f-557936b85334.jpg");

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  @Value("${search.kafka.topic.product-created}")
  private String productCreatedTopic;

  @Value("${search.kafka.topic.product-updated}")
  private String productUpdatedTopic;

  @Value("${search.kafka.topic.product-deleted}")
  private String productDeletedTopic;

  public TopicProduceTestResponse produceSamples() {
    Instant batchStartedAt = Instant.now();
    for (int index = 1; index <= SAMPLE_COUNT; index++) {
      Map<String, Object> createdPayload = sampleCreatedPayload(index, batchStartedAt);
      String key = String.valueOf(createdPayload.get("id"));

      publish(productCreatedTopic, key, createdPayload, "product-created");
      publish(productUpdatedTopic, key, updatedPayload(createdPayload), "product-updated");
      publish(productDeletedTopic, key, deletedPayload(createdPayload), "product-deleted");
    }

    int totalCount = SAMPLE_COUNT * 3;
    return new TopicProduceTestResponse(
        "검색 상품 Kafka 토픽 샘플 발행을 요청했습니다.",
        productCreatedTopic,
        productUpdatedTopic,
        productDeletedTopic,
        SAMPLE_COUNT,
        SAMPLE_COUNT,
        SAMPLE_COUNT,
        totalCount);
  }

  public List<ProductSearchSyncTestResponse> searchSyncTestProducts(Instant changedAfter) {
    Instant batchStartedAt = nextBatchStartedAt(changedAfter);
    List<ProductSearchSyncTestResponse> products = new ArrayList<>();
    List<Map<String, Object>> createdPayloads = new ArrayList<>();

    for (int index = 1; index <= SYNC_INSERT_COUNT; index++) {
      Map<String, Object> createdPayload = sampleCreatedPayload(index, batchStartedAt);
      createdPayloads.add(createdPayload);
      products.add(toResponse(ProductSyncOperation.INSERT, createdPayload));
    }
    for (int index = 0; index < SYNC_UPDATE_COUNT; index++) {
      products.add(
          toResponse(ProductSyncOperation.UPDATE, updatedPayload(createdPayloads.get(index))));
    }
    for (int index = SYNC_UPDATE_COUNT; index < SYNC_UPDATE_COUNT + SYNC_DELETE_COUNT; index++) {
      products.add(
          toResponse(ProductSyncOperation.DELETE, deletedPayload(createdPayloads.get(index))));
    }

    return products.stream()
        .filter(product -> product.latestEventAt().isAfter(changedAfter))
        .toList();
  }

  private Instant nextBatchStartedAt(Instant changedAfter) {
    Instant now = Instant.now();
    Instant firstUnprocessedInstant = changedAfter.plus(EVENT_TIME_GAP);
    return now.isAfter(firstUnprocessedInstant) ? now : firstUnprocessedInstant;
  }

  private Map<String, Object> sampleCreatedPayload(int index, Instant batchStartedAt) {
    UUID id =
        UUID.nameUUIDFromBytes(
            ("search-product-topic-produce-test-" + batchStartedAt + "-" + index)
                .getBytes(StandardCharsets.UTF_8));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id.toString());
    payload.put("createdAt", batchStartedAt.toString());
    payload.put("name", "카프카 샘플 상품 " + index);
    payload.put("description", "검색 모듈 Kafka producer 테스트용 상품 payload " + index);
    payload.put("categoryId", sampleCategoryId(index));
    payload.put("categoryName", sampleCategoryName(index));
    payload.put("sellerName", "샘플 판매자 " + ((index % 3) + 1));
    payload.put("price", 10000L + (index * 1000L));
    payload.put("thumbnailKey", randomThumbnailUrl());
    payload.put("imgDescription", sampleImgDescription(index));
    payload.put("updatedAt", batchStartedAt.toString());
    payload.put("deletedAt", null);
    return payload;
  }

  private Map<String, Object> updatedPayload(Map<String, Object> createdPayload) {
    Map<String, Object> payload = new LinkedHashMap<>(createdPayload);
    Instant createdAt = Instant.parse((String) createdPayload.get("createdAt"));
    payload.put("name", createdPayload.get("name") + " 수정");
    payload.put("description", createdPayload.get("description") + " - 수정 이벤트");
    payload.put("price", ((Long) createdPayload.get("price")) + 500L);
    payload.put("updatedAt", createdAt.plus(EVENT_TIME_GAP).toString());
    payload.put("deletedAt", null);
    return payload;
  }

  private Map<String, Object> deletedPayload(Map<String, Object> createdPayload) {
    Map<String, Object> payload = new LinkedHashMap<>(createdPayload);
    Instant createdAt = Instant.parse((String) createdPayload.get("createdAt"));
    Instant updatedAt = createdAt.plus(EVENT_TIME_GAP);
    payload.put("description", createdPayload.get("description") + " - 삭제 이벤트");
    payload.put("updatedAt", updatedAt.toString());
    payload.put("deletedAt", updatedAt.plus(EVENT_TIME_GAP).toString());
    return payload;
  }

  private String sampleCategoryId(int index) {
    return UUID.nameUUIDFromBytes(
            ("search-product-topic-produce-test-category-" + (index % 5))
                .getBytes(StandardCharsets.UTF_8))
        .toString();
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

  private String sampleImgDescription(int index) {
    return "카프카 샘플 상품 " + index + "의 가상 이미지 설명입니다.";
  }

  private ProductSearchSyncTestResponse toResponse(
      ProductSyncOperation operation, Map<String, Object> payload) {
    return new ProductSearchSyncTestResponse(
        operation,
        (String) payload.get("id"),
        (String) payload.get("name"),
        (String) payload.get("description"),
        (String) payload.get("categoryId"),
        (String) payload.get("categoryName"),
        (String) payload.get("sellerName"),
        (Long) payload.get("price"),
        (String) payload.get("thumbnailKey"),
        (String) payload.get("imgDescription"),
        instantValue(payload.get("createdAt")),
        instantValue(payload.get("updatedAt")),
        instantValue(payload.get("deletedAt")));
  }

  private Instant instantValue(Object value) {
    if (value == null) {
      return null;
    }
    return Instant.parse((String) value);
  }

  private String randomThumbnailUrl() {
    return SAMPLE_THUMBNAIL_URLS.get(
        ThreadLocalRandom.current().nextInt(SAMPLE_THUMBNAIL_URLS.size()));
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

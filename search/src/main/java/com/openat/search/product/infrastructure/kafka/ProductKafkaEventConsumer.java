package com.openat.search.product.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.product.application.service.AiImageService;
import com.openat.search.product.application.service.ProductEmbeddingService;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import com.openat.search.product.presentation.dto.AiImageAnalyzeResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductKafkaEventConsumer {

  private final ObjectMapper objectMapper;
  private final ProductSearchDocumentRepository productSearchDocumentRepository;
  private final ProductEmbeddingService productEmbeddingService;
  private final AiImageService aiImageService;

  @KafkaListener(
      topics = "${search.kafka.topic.product-created}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void consumeProductCreated(ConsumerRecord<String, String> record) {
    saveProductDocument("product-created", record);
  }

  @KafkaListener(
      topics = "${search.kafka.topic.product-updated}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void consumeProductUpdated(ConsumerRecord<String, String> record) {
    saveProductDocument("product-updated", record);
  }

  @KafkaListener(
      topics = "${search.kafka.topic.product-deleted}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void consumeProductDeleted(ConsumerRecord<String, String> record) {
    saveProductDocument("product-deleted", record);
  }

  private void saveProductDocument(String eventType, ConsumerRecord<String, String> record) {
    parseProductDocument(eventType, record)
        .ifPresent(
            productDocument -> {
              ProductDocument documentToSave = applyEmbeddingOrOriginal(eventType, productDocument);
              productSearchDocumentRepository.save(documentToSave);
              log.info(
                  "Search product Kafka event indexed to Elasticsearch. eventType={}, topic={}, partition={}, offset={}, key={}, productId={}, hasEmbedding={}",
                  eventType,
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  record.key(),
                  documentToSave.id(),
                  documentToSave.embedding() != null && documentToSave.embedding().length > 0);
            });
  }

  private Optional<ProductDocument> parseProductDocument(
      String eventType, ConsumerRecord<String, String> record) {
    return parseJsonPayload(eventType, record)
        .flatMap(
            jsonPayload -> {
              logProductEvent(eventType, record, jsonPayload);

              String id = textValueOrDefault(jsonPayload, virtualId(eventType, record), "id");
              Instant createdAt =
                  instantValueOrDefault(
                      jsonPayload, virtualCreatedAt(record), "createdAt", "created_at");
              Instant updatedAt =
                  instantValueOrDefault(jsonPayload, createdAt, "updatedAt", "updated_at");

              return Optional.of(
                  new ProductDocument(
                      id,
                      textValueOrDefault(jsonPayload, "Virtual product " + id, "name"),
                      textValueOrDefault(
                          jsonPayload, "Virtual description for product " + id, "description"),
                      textValueOrDefault(
                          jsonPayload, virtualCategoryId(id), "categoryId", "category_id"),
                      textValueOrDefault(
                          jsonPayload, "Virtual Category", "categoryName", "category_nm"),
                      textValueOrDefault(
                          jsonPayload, "Virtual Seller", "sellerName", "seller_name"),
                      longValueOrDefault(jsonPayload, 0L, "price"),
                      textValueOrDefault(
                          jsonPayload,
                          "virtual-thumbnail-" + id + ".jpg",
                          "thumbnailKey",
                          "thumbnail_key"),
                      textValueOrDefault(
                          jsonPayload,
                          "Virtual image description for product " + id,
                          "imgDescription",
                          "img_description"),
                      null,
                      createdAt,
                      updatedAt,
                      deletedAtValue(eventType, jsonPayload, updatedAt)));
            });
  }

  private Optional<JsonNode> parseJsonPayload(
      String eventType, ConsumerRecord<String, String> record) {
    String payload = record.value();

    try {
      return Optional.of(objectMapper.readTree(payload));
    } catch (JsonProcessingException e) {
      log.warn(
          "Search product Kafka event consumed but payload is not JSON. eventType={}, topic={}, partition={}, offset={}, key={}, payload={}",
          eventType,
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          payload);
      return Optional.empty();
    }
  }

  private void logProductEvent(
      String eventType, ConsumerRecord<String, String> record, JsonNode jsonPayload) {
    log.info(
        "Search product Kafka event consumed. eventType={}, topic={}, partition={}, offset={}, key={}, id={}, createdAt={}, name={}, description={}, categoryId={}, categoryName={}, sellerName={}, price={}, thumbnailKey={}, imgDescription={}, updatedAt={}, deletedAt={}",
        eventType,
        record.topic(),
        record.partition(),
        record.offset(),
        record.key(),
        textValue(jsonPayload, "id"),
        textValue(jsonPayload, "createdAt", "created_at"),
        textValue(jsonPayload, "name"),
        textValue(jsonPayload, "description"),
        textValue(jsonPayload, "categoryId", "category_id"),
        textValue(jsonPayload, "categoryName", "category_nm"),
        textValue(jsonPayload, "sellerName", "seller_name"),
        textValue(jsonPayload, "price"),
        textValue(jsonPayload, "thumbnailKey", "thumbnail_key"),
        textValue(jsonPayload, "imgDescription", "img_description"),
        textValue(jsonPayload, "updatedAt", "updated_at"),
        textValue(jsonPayload, "deletedAt", "deleted_at"));
  }

  private ProductDocument applyEmbeddingOrOriginal(
      String eventType, ProductDocument productDocument) {
    try {

      AiImageAnalyzeResponse aiImageAnalyzeResponse =
          aiImageService.analyzeImageUrl(productDocument.thumbnailKey(), "");
      ProductDocument analyzedProductDocument =
          productDocument.withImgDescription(aiImageAnalyzeResponse.answer());

      return productEmbeddingService.applyEmbedding(analyzedProductDocument);
    } catch (RuntimeException e) {
      log.warn(
          "Search product Kafka event embedding failed. eventType={}, productId={}, name={}, description={}",
          eventType,
          productDocument.id(),
          productDocument.name(),
          productDocument.description(),
          e);
      return productDocument;
    }
  }

  private String textValue(JsonNode jsonPayload, String fieldName) {
    return textValue(jsonPayload, fieldName, new String[0]);
  }

  private String textValue(JsonNode jsonPayload, String fieldName, String... fallbackFieldNames) {
    JsonNode field = firstPresentField(jsonPayload, fieldName, fallbackFieldNames);
    if (field == null || field.isNull()) {
      return "";
    }
    return field.asText();
  }

  private String textValueOrDefault(
      JsonNode jsonPayload, String defaultValue, String fieldName, String... fallbackFieldNames) {
    String value = textValue(jsonPayload, fieldName, fallbackFieldNames);
    if (value.isBlank()) {
      return defaultValue;
    }
    return value;
  }

  private Long longValueOrDefault(JsonNode jsonPayload, Long defaultValue, String fieldName) {
    JsonNode field = firstPresentField(jsonPayload, fieldName);
    if (field == null || field.isNull()) {
      return defaultValue;
    }
    if (field.isNumber()) {
      return field.asLong();
    }
    String value = field.asText();
    if (value.isBlank()) {
      return defaultValue;
    }
    return Long.parseLong(value);
  }

  private Instant instantValueOrDefault(
      JsonNode jsonPayload, Instant defaultValue, String fieldName, String... fallbackFieldNames) {
    String value = textValue(jsonPayload, fieldName, fallbackFieldNames);
    if (value.isBlank()) {
      return defaultValue;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      log.warn(
          "Search product Kafka event date parse failed. fieldName={}, value={}", fieldName, value);
      return defaultValue;
    }
  }

  private Instant deletedAtValue(String eventType, JsonNode jsonPayload, Instant updatedAt) {
    Instant defaultValue = "product-deleted".equals(eventType) ? updatedAt : null;
    return instantValueOrDefault(jsonPayload, defaultValue, "deletedAt", "deleted_at");
  }

  private Instant virtualCreatedAt(ConsumerRecord<String, String> record) {
    long seconds = Math.max(0L, record.offset());
    return Instant.parse("2026-07-08T00:00:00Z").plusSeconds(seconds);
  }

  private String virtualId(String eventType, ConsumerRecord<String, String> record) {
    String source =
        "%s:%s:%d:%d:%s"
            .formatted(
                eventType, record.topic(), record.partition(), record.offset(), record.key());
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private String virtualCategoryId(String productId) {
    return UUID.nameUUIDFromBytes(
            ("virtual-category-" + productId).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private JsonNode firstPresentField(
      JsonNode jsonPayload, String fieldName, String... fallbackFieldNames) {
    JsonNode field = jsonPayload.get(fieldName);
    if (field != null) {
      return field;
    }
    for (String fallbackFieldName : fallbackFieldNames) {
      field = jsonPayload.get(fallbackFieldName);
      if (field != null) {
        return field;
      }
    }
    return null;
  }
}

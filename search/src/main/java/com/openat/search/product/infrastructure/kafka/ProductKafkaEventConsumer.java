package com.openat.search.product.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.product.application.service.ProductEmbeddingService;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
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
    deleteProductDocument("product-deleted", record);
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

  private void deleteProductDocument(String eventType, ConsumerRecord<String, String> record) {
    parseJsonPayload(eventType, record)
        .map(jsonPayload -> textValue(jsonPayload, "id"))
        .filter(id -> !id.isBlank())
        .ifPresentOrElse(
            productId -> {
              productSearchDocumentRepository.deleteById(productId);
              log.info(
                  "Search product Kafka event deleted from Elasticsearch. eventType={}, topic={}, partition={}, offset={}, key={}, productId={}",
                  eventType,
                  record.topic(),
                  record.partition(),
                  record.offset(),
                  record.key(),
                  productId);
            },
            () ->
                log.warn(
                    "Search product Kafka delete event ignored because id is blank. eventType={}, topic={}, partition={}, offset={}, key={}, payload={}",
                    eventType,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.value()));
  }

  private Optional<ProductDocument> parseProductDocument(
      String eventType, ConsumerRecord<String, String> record) {
    return parseJsonPayload(eventType, record)
        .flatMap(
            jsonPayload -> {
              logProductEvent(eventType, record, jsonPayload);

              String id = textValue(jsonPayload, "id");
              if (id.isBlank()) {
                log.warn(
                    "Search product Kafka event ignored because id is blank. eventType={}, topic={}, partition={}, offset={}, key={}, payload={}",
                    eventType,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    record.value());
                return Optional.empty();
              }

              return Optional.of(
                  new ProductDocument(
                      id,
                      textValue(jsonPayload, "name"),
                      textValue(jsonPayload, "description"),
                      textValue(jsonPayload, "category_nm"),
                      longValue(jsonPayload, "price"),
                      textValue(jsonPayload, "thumbnail_key"),
                      null,
                      instantValue(jsonPayload, "created_at"),
                      instantValue(jsonPayload, "updated_at")));
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
        "Search product Kafka event consumed. eventType={}, topic={}, partition={}, offset={}, key={}, id={}, created_at={}, name={}, description={}, price={}, thumbnail_key={}, updated_at={}, category_nm={}",
        eventType,
        record.topic(),
        record.partition(),
        record.offset(),
        record.key(),
        textValue(jsonPayload, "id"),
        textValue(jsonPayload, "created_at"),
        textValue(jsonPayload, "name"),
        textValue(jsonPayload, "description"),
        textValue(jsonPayload, "price"),
        textValue(jsonPayload, "thumbnail_key"),
        textValue(jsonPayload, "updated_at"),
        textValue(jsonPayload, "category_nm"));
  }

  private ProductDocument applyEmbeddingOrOriginal(
      String eventType, ProductDocument productDocument) {
    try {
      return productEmbeddingService.applyEmbedding(productDocument);
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
    JsonNode field = jsonPayload.get(fieldName);
    if (field == null || field.isNull()) {
      return "";
    }
    return field.asText();
  }

  private Long longValue(JsonNode jsonPayload, String fieldName) {
    JsonNode field = jsonPayload.get(fieldName);
    if (field == null || field.isNull()) {
      return null;
    }
    if (field.isNumber()) {
      return field.asLong();
    }
    String value = field.asText();
    if (value.isBlank()) {
      return null;
    }
    return Long.parseLong(value);
  }

  private Instant instantValue(JsonNode jsonPayload, String fieldName) {
    String value = textValue(jsonPayload, fieldName);
    if (value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      log.warn(
          "Search product Kafka event date parse failed. fieldName={}, value={}", fieldName, value);
      return null;
    }
  }
}

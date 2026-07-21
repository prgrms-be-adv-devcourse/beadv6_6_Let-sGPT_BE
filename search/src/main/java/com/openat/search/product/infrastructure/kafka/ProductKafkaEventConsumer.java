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
    saveUpsertProductDocument("product-created", record);
  }

  @KafkaListener(
      topics = "${search.kafka.topic.product-updated}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void consumeProductUpdated(ConsumerRecord<String, String> record) {
    saveUpsertProductDocument("product-updated", record);
  }

  @KafkaListener(
      topics = "${search.kafka.topic.product-deleted}",
      groupId = "${spring.kafka.consumer.group-id}")
  public void consumeProductDeleted(ConsumerRecord<String, String> record) {
    markProductDeleted(record);
  }

  private void saveUpsertProductDocument(String eventType, ConsumerRecord<String, String> record) {
    parseUpsertProductDocument(eventType, record)
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

  private void markProductDeleted(ConsumerRecord<String, String> record) {
    String eventType = "product-deleted";
    parseJsonPayload(eventType, record)
        .ifPresent(
            jsonPayload -> {
              logProductEvent(eventType, record, jsonPayload);

              Optional<String> productId = productId(eventType, record, jsonPayload);
              Optional<Instant> deletedAt = instantValue(jsonPayload, "deletedAt", "deleted_at");
              if (productId.isEmpty() || deletedAt.isEmpty()) {
                log.warn(
                    "Search product delete Kafka event skipped because required fields are missing or invalid. topic={}, partition={}, offset={}, key={}, id={}, deletedAt={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    textValue(jsonPayload, "id"),
                    textValue(jsonPayload, "deletedAt", "deleted_at"));
                return;
              }

              productSearchDocumentRepository
                  .findById(productId.get())
                  .ifPresentOrElse(
                      existingDocument -> {
                        ProductDocument deletedDocument =
                            existingDocument.withDeletedAt(deletedAt.get());
                        productSearchDocumentRepository.save(deletedDocument);
                        log.info(
                            "Search product Kafka delete event reflected in Elasticsearch. topic={}, partition={}, offset={}, key={}, productId={}, deletedAt={}",
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            record.key(),
                            productId.get(),
                            deletedAt.get());
                      },
                      () ->
                          log.warn(
                              "Search product Kafka delete event skipped because the Elasticsearch document does not exist. topic={}, partition={}, offset={}, key={}, productId={}",
                              record.topic(),
                              record.partition(),
                              record.offset(),
                              record.key(),
                              productId.get()));
            });
  }

  private Optional<ProductDocument> parseUpsertProductDocument(
      String eventType, ConsumerRecord<String, String> record) {
    return parseJsonPayload(eventType, record)
        .flatMap(
            jsonPayload -> {
              logProductEvent(eventType, record, jsonPayload);

              Optional<String> id = productId(eventType, record, jsonPayload);
              String name = textValue(jsonPayload, "name");
              Optional<Long> price = longValue(jsonPayload, "price");
              Optional<Instant> createdAt = instantValue(jsonPayload, "createdAt", "created_at");
              Optional<Instant> updatedAt = instantValue(jsonPayload, "updatedAt", "updated_at");

              if (id.isEmpty()
                  || name.isBlank()
                  || price.isEmpty()
                  || createdAt.isEmpty()
                  || updatedAt.isEmpty()) {
                log.warn(
                    "Search product upsert Kafka event skipped because required fields are missing or invalid. eventType={}, topic={}, partition={}, offset={}, key={}, id={}, name={}, price={}, createdAt={}, updatedAt={}",
                    eventType,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    textValue(jsonPayload, "id"),
                    name,
                    textValue(jsonPayload, "price"),
                    textValue(jsonPayload, "createdAt", "created_at"),
                    textValue(jsonPayload, "updatedAt", "updated_at"));
                return Optional.empty();
              }

              return Optional.of(
                  new ProductDocument(
                      id.get(),
                      name,
                      nullableTextValue(jsonPayload, "description"),
                      nullableTextValue(jsonPayload, "categoryId", "category_id"),
                      nullableTextValue(jsonPayload, "categoryName", "category_nm"),
                      nullableTextValue(jsonPayload, "sellerName", "seller_name"),
                      price.get(),
                      nullableTextValue(jsonPayload, "thumbnailKey", "thumbnail_key"),
                      nullableTextValue(jsonPayload, "imgDescription", "img_description"),
                      null,
                      createdAt.get(),
                      updatedAt.get(),
                      null));
            });
  }

  private Optional<String> productId(
      String eventType, ConsumerRecord<String, String> record, JsonNode jsonPayload) {
    String productId = textValue(jsonPayload, "id");
    if (productId.isBlank()) {
      return Optional.empty();
    }

    if (record.key() != null && !record.key().isBlank() && !record.key().equals(productId)) {
      log.warn(
          "Search product Kafka event skipped because key and product id do not match. eventType={}, topic={}, partition={}, offset={}, key={}, productId={}",
          eventType,
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          productId);
      return Optional.empty();
    }
    return Optional.of(productId);
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
    return textValue(jsonPayload, fieldName, new String[0]);
  }

  private String textValue(JsonNode jsonPayload, String fieldName, String... fallbackFieldNames) {
    JsonNode field = firstPresentField(jsonPayload, fieldName, fallbackFieldNames);
    if (field == null || field.isNull()) {
      return "";
    }
    return field.asText();
  }

  private String nullableTextValue(
      JsonNode jsonPayload, String fieldName, String... fallbackFieldNames) {
    String value = textValue(jsonPayload, fieldName, fallbackFieldNames);
    return value.isBlank() ? null : value;
  }

  private Optional<Long> longValue(JsonNode jsonPayload, String fieldName) {
    JsonNode field = firstPresentField(jsonPayload, fieldName);
    if (field == null || field.isNull()) {
      return Optional.empty();
    }
    if (field.isNumber()) {
      return Optional.of(field.asLong());
    }
    String value = field.asText();
    if (value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException e) {
      log.warn(
          "Search product Kafka event number parse failed. fieldName={}, value={}",
          fieldName,
          value);
      return Optional.empty();
    }
  }

  private Optional<Instant> instantValue(
      JsonNode jsonPayload, String fieldName, String... fallbackFieldNames) {
    String value = textValue(jsonPayload, fieldName, fallbackFieldNames);
    if (value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value));
    } catch (DateTimeParseException e) {
      log.warn(
          "Search product Kafka event date parse failed. fieldName={}, value={}", fieldName, value);
      return Optional.empty();
    }
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

package com.openat.product.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.infrastructure.kafka.event.ProductDeletedEventPayload;
import com.openat.product.infrastructure.kafka.event.ProductUpsertEventPayload;
import com.openat.seller.application.usecase.SellerStoreQueryUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class ProductKafkaEventPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final SellerStoreQueryUseCase sellerStoreQueryUseCase;
  private final String productCreatedTopic;
  private final String productUpdatedTopic;
  private final String productDeletedTopic;

  public ProductKafkaEventPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      SellerStoreQueryUseCase sellerStoreQueryUseCase,
      @Value("${product.kafka.topic.product-created}") String productCreatedTopic,
      @Value("${product.kafka.topic.product-updated}") String productUpdatedTopic,
      @Value("${product.kafka.topic.product-deleted}") String productDeletedTopic) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.sellerStoreQueryUseCase = sellerStoreQueryUseCase;
    this.productCreatedTopic = productCreatedTopic;
    this.productUpdatedTopic = productUpdatedTopic;
    this.productDeletedTopic = productDeletedTopic;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProductCreated(ProductCreatedEvent event) {
    publishUpsert(productCreatedTopic, "product-created", event.product());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProductUpdated(ProductUpdatedEvent event) {
    publishUpsert(productUpdatedTopic, "product-updated", event.product());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onProductDeleted(ProductDeletedEvent event) {
    ProductDeletedEventPayload payload =
        new ProductDeletedEventPayload(event.productId(), event.deletedAt());
    publish(productDeletedTopic, "product-deleted", event.productId(), payload);
  }

  private void publishUpsert(String topic, String eventType, Product product) {
    String sellerName = findSellerName(eventType, product);
    ProductUpsertEventPayload payload = ProductUpsertEventPayload.from(product, sellerName);
    publish(topic, eventType, product.getId(), payload);
  }

  private String findSellerName(String eventType, Product product) {
    try {
      Map<UUID, String> storeNames =
          sellerStoreQueryUseCase.findStoreNames(List.of(product.getSellerId()));
      return storeNames.get(product.getSellerId());
    } catch (RuntimeException e) {
      log.warn(
          "Product Kafka event seller name lookup failed. eventType={}, productId={}",
          eventType,
          product.getId(),
          e);
      return null;
    }
  }

  private void publish(String topic, String eventType, UUID productId, Object eventPayload) {
    String payload;
    try {
      payload = objectMapper.writeValueAsString(eventPayload);
    } catch (JsonProcessingException e) {
      log.error(
          "Product Kafka event serialization failed. eventType={}, productId={}",
          eventType,
          productId,
          e);
      return;
    }

    try {
      kafkaTemplate
          .send(topic, productId.toString(), payload)
          .whenComplete(
              (result, exception) -> {
                if (exception != null) {
                  log.error(
                      "Product Kafka event publish failed. eventType={}, topic={}, productId={}",
                      eventType,
                      topic,
                      productId,
                      exception);
                  return;
                }

                RecordMetadata metadata = result.getRecordMetadata();
                log.info(
                    "Product Kafka event published. eventType={}, topic={}, partition={}, offset={}, productId={}",
                    eventType,
                    metadata.topic(),
                    metadata.partition(),
                    metadata.offset(),
                    productId);
              });
    } catch (RuntimeException e) {
      log.error(
          "Product Kafka event publish failed. eventType={}, topic={}, productId={}",
          eventType,
          topic,
          productId,
          e);
    }
  }
}

package com.openat.product.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import com.openat.product.infrastructure.kafka.event.ProductDeletedEventPayload;
import com.openat.product.infrastructure.kafka.event.ProductUpsertEventPayload;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ProductOutboxEventWriter {

  private final EntityManager entityManager;
  private final ObjectMapper objectMapper;
  private final ProductOutboxEventRepository outboxEventRepository;
  private final SellerStoreProjectionRepository sellerStoreProjectionRepository;
  private final String productCreatedTopic;
  private final String productUpdatedTopic;
  private final String productDeletedTopic;

  public ProductOutboxEventWriter(
      EntityManager entityManager,
      ObjectMapper objectMapper,
      ProductOutboxEventRepository outboxEventRepository,
      SellerStoreProjectionRepository sellerStoreProjectionRepository,
      @Value("${product.kafka.topic.product-created}") String productCreatedTopic,
      @Value("${product.kafka.topic.product-updated}") String productUpdatedTopic,
      @Value("${product.kafka.topic.product-deleted}") String productDeletedTopic) {
    this.entityManager = entityManager;
    this.objectMapper = objectMapper;
    this.outboxEventRepository = outboxEventRepository;
    this.sellerStoreProjectionRepository = sellerStoreProjectionRepository;
    this.productCreatedTopic = productCreatedTopic;
    this.productUpdatedTopic = productUpdatedTopic;
    this.productDeletedTopic = productDeletedTopic;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onProductCreated(ProductCreatedEvent event) {
    writeUpsert(productCreatedTopic, event.product());
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onProductUpdated(ProductUpdatedEvent event) {
    writeUpsert(productUpdatedTopic, event.product());
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onProductDeleted(ProductDeletedEvent event) {
    entityManager.flush();
    ProductDeletedEventPayload payload =
        new ProductDeletedEventPayload(event.productId(), event.deletedAt());
    save(productDeletedTopic, event.productId(), event.aggregateSequence(), payload);
  }

  private void writeUpsert(String topic, Product product) {
    entityManager.flush();
    String sellerName =
        sellerStoreProjectionRepository
            .findById(product.getSellerId())
            .map(projection -> projection.getStoreName())
            .orElse(null);
    ProductUpsertEventPayload payload = ProductUpsertEventPayload.from(product, sellerName);
    save(topic, product.getId(), product.currentSearchSnapshotSequence(), payload);
  }

  private void save(
      String topic, UUID aggregateId, long aggregateSequence, Object eventPayload) {
    String payload;
    try {
      payload = objectMapper.writeValueAsString(eventPayload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("상품 변경 outbox payload 직렬화에 실패했습니다.", exception);
    }

    ProductOutboxEvent outboxEvent =
        ProductOutboxEvent.record()
            .aggregateId(aggregateId)
            .aggregateSequence(aggregateSequence)
            .topic(topic)
            .payload(payload)
            .build();
    outboxEventRepository.save(outboxEvent);
  }
}

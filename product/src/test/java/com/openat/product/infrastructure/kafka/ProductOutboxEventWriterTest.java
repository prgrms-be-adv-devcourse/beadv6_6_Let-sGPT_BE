package com.openat.product.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.config.JacksonConfig;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.model.ProductOutboxEventStatus;
import com.openat.product.domain.model.SellerStoreProjection;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 outbox 이벤트 기록기")
class ProductOutboxEventWriterTest {

  private static final String CREATED_TOPIC = "product.created.events";
  private static final String UPDATED_TOPIC = "product.updated.events";
  private static final String DELETED_TOPIC = "product.deleted.events";

  @Mock private EntityManager entityManager;
  @Mock private ProductOutboxEventRepository outboxEventRepository;
  @Mock private SellerStoreProjectionRepository sellerStoreProjectionRepository;

  private ObjectMapper objectMapper;
  private ProductOutboxEventWriter writer;

  @BeforeEach
  void setUp() {
    objectMapper = new JacksonConfig().objectMapper();
    writer =
        new ProductOutboxEventWriter(
            entityManager,
            objectMapper,
            outboxEventRepository,
            sellerStoreProjectionRepository,
            CREATED_TOPIC,
            UPDATED_TOPIC,
            DELETED_TOPIC);
  }

  @Test
  @DisplayName("상품 생성 스냅샷을 기존 topic과 payload 계약의 PENDING outbox로 기록한다")
  void onProductCreated_recordsCompatiblePayload() throws Exception {
    UUID sellerId = UUID.randomUUID();
    Product product = persistedProduct(sellerId);
    SellerStoreProjection projection =
        SellerStoreProjection.project()
            .sellerInfoId(sellerId)
            .storeName("스프링 스튜디오")
            .build();
    given(sellerStoreProjectionRepository.findById(sellerId))
        .willReturn(Optional.of(projection));

    writer.onProductCreated(new ProductCreatedEvent(product));

    ProductOutboxEvent outboxEvent = capturedEvent();
    assertThat(outboxEvent.getAggregateId()).isEqualTo(product.getId());
    assertThat(outboxEvent.getAggregateSequence()).isEqualTo(1L);
    assertThat(outboxEvent.getTopic()).isEqualTo(CREATED_TOPIC);
    assertThat(outboxEvent.getStatus()).isEqualTo(ProductOutboxEventStatus.PENDING);

    JsonNode payload = objectMapper.readTree(outboxEvent.getPayload());
    assertThat(payload.get("id").asText()).isEqualTo(product.getId().toString());
    assertThat(payload.get("sellerId").asText()).isEqualTo(sellerId.toString());
    assertThat(payload.get("sellerName").asText()).isEqualTo("스프링 스튜디오");
    assertThat(payload.has("type")).isFalse();
    assertThat(payload.has("sourceVersion")).isFalse();
  }

  @Test
  @DisplayName("상품 수정 스냅샷은 기존 수정 topic으로 기록한다")
  void onProductUpdated_recordsUpdatedTopic() {
    UUID sellerId = UUID.randomUUID();
    Product product = persistedProduct(sellerId);
    ReflectionTestUtils.setField(product, "searchSnapshotSequence", 2L);
    given(sellerStoreProjectionRepository.findById(sellerId)).willReturn(Optional.empty());

    writer.onProductUpdated(new ProductUpdatedEvent(product));

    ProductOutboxEvent outboxEvent = capturedEvent();
    assertThat(outboxEvent.getAggregateSequence()).isEqualTo(2L);
    assertThat(outboxEvent.getTopic()).isEqualTo(UPDATED_TOPIC);
  }

  @Test
  @DisplayName("상품 삭제를 기존 삭제 topic과 payload 계약으로 기록한다")
  void onProductDeleted_recordsCompatiblePayload() throws Exception {
    UUID productId = UUID.randomUUID();
    Instant deletedAt = Instant.parse("2026-07-31T00:00:00Z");

    writer.onProductDeleted(new ProductDeletedEvent(productId, 7L, deletedAt));

    ProductOutboxEvent outboxEvent = capturedEvent();
    assertThat(outboxEvent.getAggregateId()).isEqualTo(productId);
    assertThat(outboxEvent.getAggregateSequence()).isEqualTo(7L);
    assertThat(outboxEvent.getTopic()).isEqualTo(DELETED_TOPIC);

    JsonNode payload = objectMapper.readTree(outboxEvent.getPayload());
    assertThat(payload.get("id").asText()).isEqualTo(productId.toString());
    assertThat(payload.get("deletedAt").asText()).isEqualTo(deletedAt.toString());
    assertThat(payload.has("type")).isFalse();
    assertThat(payload.has("sourceVersion")).isFalse();
  }

  private ProductOutboxEvent capturedEvent() {
    ArgumentCaptor<ProductOutboxEvent> eventCaptor =
        ArgumentCaptor.forClass(ProductOutboxEvent.class);
    then(entityManager).should().flush();
    then(outboxEventRepository).should().save(eventCaptor.capture());
    return eventCaptor.getValue();
  }

  private Product persistedProduct(UUID sellerId) {
    Product product =
        Product.create()
            .sellerId(sellerId)
            .name("검색 투영 상품")
            .description("설명")
            .price(10_000L)
            .build();
    ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(
        product, "createdAt", Instant.parse("2026-07-30T00:00:00Z"));
    ReflectionTestUtils.setField(
        product, "updatedAt", Instant.parse("2026-07-31T00:00:00Z"));
    return product;
  }
}

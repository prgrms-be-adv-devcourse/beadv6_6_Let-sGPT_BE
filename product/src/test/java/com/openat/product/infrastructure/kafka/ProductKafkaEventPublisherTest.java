package com.openat.product.infrastructure.kafka;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.category.domain.model.Category;
import com.openat.config.JacksonConfig;
import com.openat.product.domain.event.ProductCreatedEvent;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.seller.application.usecase.SellerStoreQueryUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 Kafka 이벤트 발행자")
class ProductKafkaEventPublisherTest {

  private static final String CREATED_TOPIC = "product.created.events";
  private static final String UPDATED_TOPIC = "product.updated.events";
  private static final String DELETED_TOPIC = "product.deleted.events";
  private static final Instant CREATED_AT = Instant.parse("2026-07-14T00:00:00Z");
  private static final Instant UPDATED_AT = Instant.parse("2026-07-15T00:00:00Z");

  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  @Mock private SellerStoreQueryUseCase sellerStoreQueryUseCase;

  private ObjectMapper objectMapper;
  private ProductKafkaEventPublisher publisher;

  @BeforeEach
  void setUp() {
    objectMapper = new JacksonConfig().objectMapper();
    publisher =
        new ProductKafkaEventPublisher(
            kafkaTemplate,
            objectMapper,
            sellerStoreQueryUseCase,
            CREATED_TOPIC,
            UPDATED_TOPIC,
            DELETED_TOPIC);
  }

  @Nested
  @DisplayName("상품 생성 이벤트")
  class ProductCreated {

    @Test
    @DisplayName("검색 상품 필드를 생성 토픽에 상품 ID 키로 발행한다")
    void onProductCreated_validEvent_publishesSearchPayload() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      UUID categoryId = UUID.randomUUID();
      String sellerName = "오픈앳 스토어";
      Product product = categorizedProduct(sellerId, categoryId);
      given(sellerStoreQueryUseCase.findStoreNames(List.of(sellerId)))
          .willReturn(Map.of(sellerId, sellerName));
      given(kafkaTemplate.send(eq(CREATED_TOPIC), anyString(), anyString()))
          .willReturn(successfulSend(CREATED_TOPIC));

      // when
      publisher.onProductCreated(new ProductCreatedEvent(product));

      // then
      ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
      then(kafkaTemplate)
          .should()
          .send(eq(CREATED_TOPIC), eq(product.getId().toString()), payloadCaptor.capture());

      JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
      assertThat(payload.get("id").asText()).isEqualTo(product.getId().toString());
      assertThat(payload.get("name").asText()).isEqualTo(product.getName());
      assertThat(payload.get("description").asText()).isEqualTo(product.getDescription());
      assertThat(payload.get("categoryId").asText()).isEqualTo(categoryId.toString());
      assertThat(payload.get("categoryName").asText()).isEqualTo("의류");
      assertThat(payload.get("sellerName").asText()).isEqualTo(sellerName);
      assertThat(payload.get("price").asLong()).isEqualTo(product.getPrice());
      assertThat(payload.get("thumbnailKey").asText()).isEqualTo(product.getThumbnailKey());
      assertThat(payload.get("createdAt").asText()).isEqualTo(CREATED_AT.toString());
      assertThat(payload.get("updatedAt").asText()).isEqualTo(UPDATED_AT.toString());
      assertThat(payload.has("deletedAt")).isFalse();
    }

    @Test
    @DisplayName("판매자 식별자를 생성 토픽 payload에 포함해 발행한다")
    void onProductCreated_validEvent_publishesSellerId() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      Product product = categorizedProduct(sellerId, UUID.randomUUID());
      given(sellerStoreQueryUseCase.findStoreNames(List.of(sellerId)))
          .willReturn(Map.of(sellerId, "오픈앳 스토어"));
      given(kafkaTemplate.send(eq(CREATED_TOPIC), anyString(), anyString()))
          .willReturn(successfulSend(CREATED_TOPIC));

      // when
      publisher.onProductCreated(new ProductCreatedEvent(product));

      // then
      ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
      then(kafkaTemplate)
          .should()
          .send(eq(CREATED_TOPIC), eq(product.getId().toString()), payloadCaptor.capture());

      JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
      assertThat(payload.get("sellerId").asText()).isEqualTo(sellerId.toString());
    }

    @Test
    @DisplayName("판매자 표시명 조회에 실패해도 나머지 상품 필드는 생성 토픽에 발행한다")
    void onProductCreated_sellerLookupFails_publishesWithoutSellerName() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      Product product = uncategorizedProduct(sellerId);
      given(sellerStoreQueryUseCase.findStoreNames(List.of(sellerId)))
          .willThrow(new IllegalStateException("seller projection unavailable"));
      given(kafkaTemplate.send(eq(CREATED_TOPIC), anyString(), anyString()))
          .willReturn(successfulSend(CREATED_TOPIC));

      // when
      publisher.onProductCreated(new ProductCreatedEvent(product));

      // then
      ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
      then(kafkaTemplate)
          .should()
          .send(eq(CREATED_TOPIC), eq(product.getId().toString()), payloadCaptor.capture());

      JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
      assertThat(payload.get("sellerName").isNull()).isTrue();
      assertThat(payload.get("name").asText()).isEqualTo(product.getName());
    }
  }

  @Nested
  @DisplayName("상품 수정 이벤트")
  class ProductUpdated {

    @Test
    @DisplayName("미분류 상품은 카테고리 필드를 null로 수정 토픽에 발행한다")
    void onProductUpdated_uncategorizedProduct_publishesNullCategory() throws Exception {
      // given
      UUID sellerId = UUID.randomUUID();
      Product product = uncategorizedProduct(sellerId);
      given(sellerStoreQueryUseCase.findStoreNames(List.of(sellerId))).willReturn(Map.of());
      given(kafkaTemplate.send(eq(UPDATED_TOPIC), anyString(), anyString()))
          .willReturn(successfulSend(UPDATED_TOPIC));

      // when
      publisher.onProductUpdated(new ProductUpdatedEvent(product));

      // then
      ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
      then(kafkaTemplate)
          .should()
          .send(eq(UPDATED_TOPIC), eq(product.getId().toString()), payloadCaptor.capture());

      JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
      assertThat(payload.get("categoryId").isNull()).isTrue();
      assertThat(payload.get("categoryName").isNull()).isTrue();
      assertThat(payload.get("sellerName").isNull()).isTrue();
      assertThat(payload.get("updatedAt").asText()).isEqualTo(UPDATED_AT.toString());
    }
  }

  @Nested
  @DisplayName("상품 삭제 이벤트")
  class ProductDeleted {

    @Test
    @DisplayName("상품 ID와 삭제 시각만 삭제 토픽에 tombstone으로 발행한다")
    void onProductDeleted_validEvent_publishesTombstone() throws Exception {
      // given
      UUID productId = UUID.randomUUID();
      Instant deletedAt = Instant.parse("2026-07-15T01:00:00Z");
      given(kafkaTemplate.send(eq(DELETED_TOPIC), anyString(), anyString()))
          .willReturn(successfulSend(DELETED_TOPIC));

      // when
      publisher.onProductDeleted(new ProductDeletedEvent(productId, deletedAt));

      // then
      ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
      then(kafkaTemplate)
          .should()
          .send(eq(DELETED_TOPIC), eq(productId.toString()), payloadCaptor.capture());

      JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
      assertThat(payload.size()).isEqualTo(2);
      assertThat(payload.get("id").asText()).isEqualTo(productId.toString());
      assertThat(payload.get("deletedAt").asText()).isEqualTo(deletedAt.toString());
      then(sellerStoreQueryUseCase).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Kafka 비동기 발행 실패는 커밋된 요청에 예외로 전파하지 않는다")
    void onProductDeleted_kafkaSendFails_doesNotPropagate() {
      // given
      UUID productId = UUID.randomUUID();
      Instant deletedAt = Instant.parse("2026-07-15T01:00:00Z");
      CompletableFuture<SendResult<String, String>> failedSend = new CompletableFuture<>();
      failedSend.completeExceptionally(new IllegalStateException("kafka unavailable"));
      given(kafkaTemplate.send(eq(DELETED_TOPIC), anyString(), anyString())).willReturn(failedSend);

      // when
      ThrowingCallable publishEvent =
          () -> publisher.onProductDeleted(new ProductDeletedEvent(productId, deletedAt));

      // then
      assertThatCode(publishEvent).doesNotThrowAnyException();
      then(kafkaTemplate).should().send(eq(DELETED_TOPIC), eq(productId.toString()), anyString());
    }
  }

  private Product categorizedProduct(UUID sellerId, UUID categoryId) {
    Category category = Category.create().name("의류").build();
    ReflectionTestUtils.setField(category, "id", categoryId);
    Product product =
        Product.create()
            .sellerId(sellerId)
            .name("한정 후드")
            .description("검색 임베딩 대상 상품")
            .category(category)
            .price(50_000L)
            .thumbnailKey("products/hoodie.webp")
            .build();
    persist(product);
    return product;
  }

  private Product uncategorizedProduct(UUID sellerId) {
    Product product =
        Product.create()
            .sellerId(sellerId)
            .name("미분류 상품")
            .description("카테고리 없는 상품")
            .price(10_000L)
            .build();
    persist(product);
    return product;
  }

  private void persist(Product product) {
    ReflectionTestUtils.setField(product, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(product, "createdAt", CREATED_AT);
    ReflectionTestUtils.setField(product, "updatedAt", UPDATED_AT);
  }

  private CompletableFuture<SendResult<String, String>> successfulSend(String topic) {
    ProducerRecord<String, String> producerRecord = new ProducerRecord<>(topic, "key", "payload");
    RecordMetadata metadata = new RecordMetadata(new TopicPartition(topic, 0), 1L, 0, 0L, 0, 0);
    return completedFuture(new SendResult<>(producerRecord, metadata));
  }
}

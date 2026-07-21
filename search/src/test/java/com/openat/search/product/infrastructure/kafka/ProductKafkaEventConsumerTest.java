package com.openat.search.product.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.config.JacksonConfig;
import com.openat.search.product.application.service.ProductEmbeddingService;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.elasticsearch.ProductSearchDocumentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductKafkaEventConsumerTest {

  private static final String PRODUCT_ID = "19c3aa64-924f-45a2-843a-d6a73decb1e5";

  private final ProductSearchDocumentRepository repository =
      mock(ProductSearchDocumentRepository.class);
  private final ProductEmbeddingService embeddingService = mock(ProductEmbeddingService.class);
  private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();
  private final ProductKafkaEventConsumer consumer =
      new ProductKafkaEventConsumer(objectMapper, repository, embeddingService);

  @Test
  void createAndUpdateUseTheSameRealProductId() {
    when(embeddingService.applyEmbedding(any(ProductDocument.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    consumer.consumeProductCreated(
        record("product.created.events", upsertPayload("등록 상품", 10_000L)));
    consumer.consumeProductUpdated(
        record("product.updated.events", upsertPayload("수정 상품", 12_000L)));

    ArgumentCaptor<ProductDocument> captor = ArgumentCaptor.forClass(ProductDocument.class);
    verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
    List<ProductDocument> savedDocuments = captor.getAllValues();

    assertThat(savedDocuments).extracting(ProductDocument::id).containsOnly(PRODUCT_ID);
    assertThat(savedDocuments.get(0).name()).isEqualTo("등록 상품");
    assertThat(savedDocuments.get(1).name()).isEqualTo("수정 상품");
    assertThat(savedDocuments.get(1).price()).isEqualTo(12_000L);
    assertThat(savedDocuments)
        .allSatisfy(
            document -> {
              assertThat(document.name()).doesNotContainIgnoringCase("virtual");
              assertThat(document.deletedAt()).isNull();
            });
  }

  @Test
  void eventWithoutProductIdDoesNotCreateVirtualDocument() {
    String payload =
        """
        {
          "name": "ID 없는 상품",
          "price": 10000,
          "createdAt": "2026-07-15T00:00:00Z",
          "updatedAt": "2026-07-15T00:00:00Z"
        }
        """;

    consumer.consumeProductCreated(
        new ConsumerRecord<>("product.created.events", 0, 1L, null, payload));

    verify(repository, never()).save(any(ProductDocument.class));
    verifyNoInteractions(embeddingService);
  }

  @Test
  void deletePreservesExistingDocumentAndOnlySetsDeletedAt() {
    ProductDocument existingDocument = existingDocument();
    Instant deletedAt = Instant.parse("2026-07-15T02:00:00Z");
    when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(existingDocument));

    consumer.consumeProductDeleted(
        record(
            "product.deleted.events",
            """
            {
              "id": "%s",
              "deletedAt": "%s"
            }
            """
                .formatted(PRODUCT_ID, deletedAt)));

    ArgumentCaptor<ProductDocument> captor = ArgumentCaptor.forClass(ProductDocument.class);
    verify(repository).save(captor.capture());
    ProductDocument savedDocument = captor.getValue();

    assertThat(savedDocument.id()).isEqualTo(existingDocument.id());
    assertThat(savedDocument.name()).isEqualTo(existingDocument.name());
    assertThat(savedDocument.description()).isEqualTo(existingDocument.description());
    assertThat(savedDocument.price()).isEqualTo(existingDocument.price());
    assertThat(savedDocument.embedding()).isSameAs(existingDocument.embedding());
    assertThat(savedDocument.deletedAt()).isEqualTo(deletedAt);
    verifyNoInteractions(embeddingService);
  }

  @Test
  void deleteDoesNotCreateDocumentWhenProductIsMissing() {
    when(repository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

    consumer.consumeProductDeleted(
        record(
            "product.deleted.events",
            """
            {
              "id": "%s",
              "deletedAt": "2026-07-15T02:00:00Z"
            }
            """
                .formatted(PRODUCT_ID)));

    verify(repository, never()).save(any(ProductDocument.class));
    verifyNoInteractions(embeddingService);
  }

  private ConsumerRecord<String, String> record(String topic, String payload) {
    return new ConsumerRecord<>(topic, 0, 1L, PRODUCT_ID, payload);
  }

  private String upsertPayload(String name, long price) {
    return """
        {
          "id": "%s",
          "name": "%s",
          "description": "상품 설명",
          "categoryId": "29c3aa64-924f-45a2-843a-d6a73decb1e5",
          "categoryName": "테스트 카테고리",
          "sellerName": "테스트 판매자",
          "price": %d,
          "thumbnailKey": "https://example.com/product.jpg",
          "createdAt": "2026-07-15T00:00:00Z",
          "updatedAt": "2026-07-15T01:00:00Z"
        }
        """
        .formatted(PRODUCT_ID, name, price);
  }

  private ProductDocument existingDocument() {
    return new ProductDocument(
        PRODUCT_ID,
        "기존 상품",
        "기존 상품 설명",
        "29c3aa64-924f-45a2-843a-d6a73decb1e5",
        "기존 카테고리",
        "기존 판매자",
        10_000L,
        "https://example.com/product.jpg",
        "기존 이미지 설명",
        new float[] {0.1F, 0.2F},
        Instant.parse("2026-07-15T00:00:00Z"),
        Instant.parse("2026-07-15T01:00:00Z"),
        null);
  }
}

package com.openat.search.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.vector.InferenceServerProductEmbeddingGenerator;
import com.openat.search.product.infrastructure.vector.NoOpProductEmbeddingGenerator;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ProductEmbeddingServiceTest {

  @Test
  void skipsImageAnalysisAndUsesBlankDescriptionForHttpsThumbnail() {
    InferenceServerProductEmbeddingGenerator embeddingGenerator =
        mock(InferenceServerProductEmbeddingGenerator.class);
    NoOpProductEmbeddingGenerator noOpGenerator = mock(NoOpProductEmbeddingGenerator.class);
    AiImageService aiImageService = mock(AiImageService.class);
    ProductEmbeddingService service =
        new ProductEmbeddingService(embeddingGenerator, noOpGenerator, aiImageService);
    ReflectionTestUtils.setField(service, "embeddingEnabled", true);
    float[] embedding = new float[1536];
    when(embeddingGenerator.generate(anyString())).thenReturn(Optional.of(embedding));

    ProductDocument result = service.applyEmbedding(document("https://example.com/product.jpg"));

    assertThat(result.imgDescription()).isEmpty();
    assertThat(result.embedding()).isSameAs(embedding);
    ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
    verify(embeddingGenerator).generate(sourceCaptor.capture());
    assertThat(sourceCaptor.getValue()).isEqualTo("상품명: 상품명\n상품 설명: 상품 설명");
    verifyNoInteractions(aiImageService, noOpGenerator);
  }

  private ProductDocument document(String thumbnailKey) {
    return new ProductDocument(
        "19c3aa64-924f-45a2-843a-d6a73decb1e5",
        "상품명",
        "상품 설명",
        null,
        null,
        null,
        10_000L,
        thumbnailKey,
        null,
        null,
        Instant.parse("2026-07-15T00:00:00Z"),
        Instant.parse("2026-07-15T01:00:00Z"),
        null);
  }
}

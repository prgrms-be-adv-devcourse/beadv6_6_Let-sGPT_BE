package com.openat.search.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.search.product.infrastructure.image.InferenceServerImageClient;
import com.openat.search.product.infrastructure.vector.InferenceServerProductEmbeddingGenerator;
import com.openat.search.product.infrastructure.vector.NoOpProductEmbeddingGenerator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class InferenceServerServiceWiringTest {

  @Test
  void imageAnalysisDelegatesToInferenceServerClient() {
    InferenceServerImageClient imageClient = mock(InferenceServerImageClient.class);
    AiImageService service = new AiImageService(imageClient, RestClient.create());
    MockMultipartFile image =
        new MockMultipartFile("image", "product.png", "image/png", new byte[] {1});
    when(imageClient.analyzeImage(image, "빨간색 상품")).thenReturn("빨간색 원형 상품");

    assertThat(service.analyze(image, "빨간색 상품").answer()).isEqualTo("빨간색 원형 상품");
    verify(imageClient).analyzeImage(image, "빨간색 상품");
  }

  @Test
  void enabledEmbeddingDelegatesToInferenceServerGenerator() {
    InferenceServerProductEmbeddingGenerator inferenceGenerator =
        mock(InferenceServerProductEmbeddingGenerator.class);
    NoOpProductEmbeddingGenerator noOpGenerator = mock(NoOpProductEmbeddingGenerator.class);
    ProductEmbeddingService service =
        new ProductEmbeddingService(inferenceGenerator, noOpGenerator);
    ReflectionTestUtils.setField(service, "embeddingEnabled", true);
    float[] embedding = new float[1536];
    when(inferenceGenerator.generate("빨간색 상품")).thenReturn(Optional.of(embedding));

    assertThat(service.embed("빨간색 상품")).contains(embedding);
    verify(inferenceGenerator).generate("빨간색 상품");
    verifyNoInteractions(noOpGenerator);
  }
}

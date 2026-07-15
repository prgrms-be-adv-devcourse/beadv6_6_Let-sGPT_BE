package com.openat.search.product.application.service;

import com.openat.search.product.application.vector.ProductEmbeddingGenerator;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.vector.AiProductEmbeddingGenerator;
import com.openat.search.product.infrastructure.vector.NoOpProductEmbeddingGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductEmbeddingService {

  private static final int OPENAI_SMALL_EMBEDDING_DIMENSIONS = 1536;

  private final AiProductEmbeddingGenerator aiProductEmbeddingGenerator;
  private final NoOpProductEmbeddingGenerator noOpProductEmbeddingGenerator;

  @Value("${openai.embedding.enabled:false}")
  private boolean embeddingEnabled;

  public Optional<float[]> embed(String text) {
    return currentGenerator().generate(text);
  }

  public ProductDocument applyEmbedding(ProductDocument productDocument) {
    if (!embeddingEnabled) {
      log.info(
          "[product-embedding] Product embedding skipped. productId={}, embeddingEnabled=false",
          productDocument.id());
      return productDocument;
    }

    String source = buildSourceText(productDocument); // 문자열 취합 (제목 + 상품설명 + 이미지 설명)

    log.info(
            "[product-es] Product source = {}",
            source);

    if (source.isBlank()) {
      throw new IllegalStateException(
          "Product embedding source text is blank. productId=" + productDocument.id());
    }

    float[] embedding =
        currentGenerator()
            .generate(source)
            .filter(this::hasEmbedding)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Product embedding is empty. productId="
                            + productDocument.id()
                            + ", embeddingEnabled="
                            + embeddingEnabled));

    validateEmbeddingDimensions(productDocument, embedding);
    log.info(
        "[product-kafka-to-es] Product embedding generated. productId={}, dimensions={}",
        productDocument.id(),
        embedding.length);
    return productDocument.withEmbedding(embedding);
  }

  private ProductEmbeddingGenerator currentGenerator() {
    return embeddingEnabled ? aiProductEmbeddingGenerator : noOpProductEmbeddingGenerator;
  }

  private String buildSourceText(ProductDocument productDocument) {
    return Arrays.stream(
            new String[] {
              productDocument.name(),
              productDocument.description(),
              productDocument.imgDescription()
            })
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.joining(" "));
  }

  private boolean hasEmbedding(float[] embedding) {
    return embedding != null && embedding.length > 0;
  }

  private void validateEmbeddingDimensions(ProductDocument productDocument, float[] embedding) {
    if (embedding.length != OPENAI_SMALL_EMBEDDING_DIMENSIONS) {
      throw new IllegalStateException(
          "Product embedding dimensions mismatch. productId="
              + productDocument.id()
              + ", expected="
              + OPENAI_SMALL_EMBEDDING_DIMENSIONS
              + ", actual="
              + embedding.length);
    }
  }
}

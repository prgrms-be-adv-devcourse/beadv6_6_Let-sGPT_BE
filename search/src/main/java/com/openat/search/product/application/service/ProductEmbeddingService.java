package com.openat.search.product.application.service;

import com.openat.search.product.application.vector.ProductEmbeddingGenerator;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import com.openat.search.product.infrastructure.vector.InferenceServerProductEmbeddingGenerator;
import com.openat.search.product.infrastructure.vector.NoOpProductEmbeddingGenerator;
import com.openat.search.product.presentation.dto.AiImageAnalyzeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductEmbeddingService {

  private static final int OPENAI_SMALL_EMBEDDING_DIMENSIONS = 1536;

  // private final AiProductEmbeddingGenerator aiProductEmbeddingGenerator;    // openai

  private final InferenceServerProductEmbeddingGenerator inferenceServerProductEmbeddingGenerator;
  private final NoOpProductEmbeddingGenerator noOpProductEmbeddingGenerator;
  private final AiImageService aiImageService;

  @Value("${openai.embedding.enabled:false}")
  private boolean embeddingEnabled;

  public Optional<float[]> embed(String text) {
    return currentGenerator().generate(text);
  }

  public ProductDocument applyEmbedding(ProductDocument productDocument) {
    ProductDocument analyzedProductDocument = analyzeThumbnail(productDocument);

    if (!embeddingEnabled) {
      log.info(
          "[product-embedding] Product embedding skipped. productId={}, embeddingEnabled=false",
          productDocument.id());
      return analyzedProductDocument;
    }

    String source = buildSourceText(analyzedProductDocument); // 문자열 취합 (제목 + 상품설명 + 이미지 설명)

    log.info("[product-es] Product source = {}", source);

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
    return analyzedProductDocument.withEmbedding(embedding);
  }

  private ProductDocument analyzeThumbnail(ProductDocument productDocument) {
    String thumbnailKey = productDocument.thumbnailKey();
    if (thumbnailKey != null && thumbnailKey.contains("https:")) {
      log.info(
          "[product-embedding] HTTPS thumbnail analysis skipped. productId={}, thumbnailKey={}",
          productDocument.id(),
          thumbnailKey);
      return productDocument.withImgDescription("");
    }

    if (!embeddingEnabled) {
      return productDocument;
    }

    AiImageAnalyzeResponse response = aiImageService.analyzeImageUrl(thumbnailKey, "");
    return productDocument.withImgDescription(response.answer());
  }

  private ProductEmbeddingGenerator currentGenerator() {
    return embeddingEnabled
        ? inferenceServerProductEmbeddingGenerator
        : noOpProductEmbeddingGenerator;
  }

  private String buildSourceText(ProductDocument productDocument) {
    return Stream.of(
            embeddingField("이미지 핵심 특징", productDocument.imgDescription()),
            embeddingField("상품명", productDocument.name()),
            embeddingField("카테고리", productDocument.categoryName()),
            embeddingField("상품 설명", productDocument.description()))
        .filter(value -> !value.isBlank())
        .collect(Collectors.joining("\n"));
  }

  private String embeddingField(String fieldName, String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return fieldName + ": " + value.trim();
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

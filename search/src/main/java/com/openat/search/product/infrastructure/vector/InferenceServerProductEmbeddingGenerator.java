package com.openat.search.product.infrastructure.vector;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openat.search.product.application.vector.ProductEmbeddingGenerator;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** OpenAI Embeddings API 규격으로 자체 추론 서버의 임베딩을 호출한다. */
@Component
public class InferenceServerProductEmbeddingGenerator implements ProductEmbeddingGenerator {

  private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
  private static final int EXPECTED_DIMENSIONS = 1536;

  private final RestClient restClient;
  private final URI embeddingsUri;
  private final String apiKey;
  private final String model;

  public InferenceServerProductEmbeddingGenerator(
      RestClient restClient,
      @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${openai.api-key:}") String apiKey,
      @Value("${openai.embedding.model:text-embedding-3-small}") String model) {
    this.restClient = restClient;
    this.embeddingsUri = endpoint(baseUrl, "embeddings");
    this.apiKey = apiKey;
    this.model = model;
  }

  @Override
  public Optional<float[]> generate(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }

    EmbeddingResponse response =
        restClient
            .post()
            .uri(embeddingsUri)
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> headers.setBearerAuth(requireApiKey()))
            .body(Map.of("model", model, "input", text))
            .retrieve()
            .body(EmbeddingResponse.class);

    if (response == null || response.data() == null || response.data().isEmpty()) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "Inference server embedding returned no data");
    }

    float[] embedding = response.data().get(0).embedding();
    if (embedding == null || embedding.length == 0) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "Inference server embedding returned empty vector");
    }
    if (embedding.length != EXPECTED_DIMENSIONS) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR,
          "Inference server embedding dimensions mismatch: expected=%d, actual=%d"
              .formatted(EXPECTED_DIMENSIONS, embedding.length));
    }
    return Optional.of(embedding);
  }

  private String requireApiKey() {
    if (apiKey == null || apiKey.isBlank()) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "Inference server API key is required");
    }
    return apiKey;
  }

  private static URI endpoint(String baseUrl, String path) {
    String normalized = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return URI.create(normalized + "/" + path);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingResponse(List<EmbeddingData> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingData(float[] embedding, int index, String object) {}
}

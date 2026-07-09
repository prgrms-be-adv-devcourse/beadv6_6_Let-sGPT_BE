package com.openat.search.product.infrastructure.vector;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openat.search.product.application.vector.ProductEmbeddingGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class AiProductEmbeddingGenerator implements ProductEmbeddingGenerator {

  private static final String OPENAI_EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";

  private final RestClient restClient;

  @Value("${openai.api-key:}")
  private String apiKey;

  @Value("${openai.embedding.model:text-embedding-3-small}")
  private String model;

  @Override
  public Optional<float[]> generate(String text) {
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }

    EmbeddingResponse response =
        restClient
            .post()
            .uri(OPENAI_EMBEDDINGS_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .headers(headers -> headers.setBearerAuth(requireApiKey()))
            .body(
                Map.of(
                    "model", model,
                    "input", text))
            .retrieve()
            .body(EmbeddingResponse.class);

    if (response == null || response.data() == null || response.data().isEmpty()) {
      throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "OpenAI embedding returned no data");
    }

    float[] embedding = response.data().get(0).embedding();
    if (embedding == null || embedding.length == 0) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "OpenAI embedding returned empty vector");
    }
    return Optional.of(embedding);
  }

  private String requireApiKey() {
    if (apiKey == null || apiKey.isBlank()) {
      throw new ResponseStatusException(
          INTERNAL_SERVER_ERROR, "OPENAI_API_KEY or OPEN_API_KEY is required");
    }
    return apiKey;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingResponse(List<EmbeddingData> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingData(float[] embedding, int index, String object) {}
}

package com.openat.search.product.infrastructure.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class InferenceServerProductEmbeddingGeneratorTest {

  private static final String BASE_URL = "http://inference.test/v1";
  private static final int EXPECTED_DIMENSIONS = 1536;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockRestServiceServer server;
  private InferenceServerProductEmbeddingGenerator generator;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    generator =
        new InferenceServerProductEmbeddingGenerator(
            builder.build(), BASE_URL + "/", "test-api-key", "text-embedding-3-small");
  }

  @Test
  void generates1536DimensionVectorThroughOpenAiEmbeddingContract() throws JsonProcessingException {
    float[] expected = vector(EXPECTED_DIMENSIONS);
    server
        .expect(requestTo(BASE_URL + "/embeddings"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .json(
                    """
                    {"model":"text-embedding-3-small","input":"빨간색 운동화"}
                    """))
        .andRespond(withSuccess(embeddingResponse(expected), MediaType.APPLICATION_JSON));

    assertThat(generator.generate("빨간색 운동화")).contains(expected);
    server.verify();
  }

  @Test
  void skipsBlankInputWithoutCallingServer() {
    assertThat(generator.generate("  ")).isEmpty();
    server.verify();
  }

  @Test
  void rejectsVectorWithUnexpectedDimensions() throws JsonProcessingException {
    server
        .expect(requestTo(BASE_URL + "/embeddings"))
        .andRespond(withSuccess(embeddingResponse(vector(1024)), MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> generator.generate("상품 설명"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("expected=1536, actual=1024");
    server.verify();
  }

  private float[] vector(int dimensions) {
    float[] vector = new float[dimensions];
    for (int index = 0; index < dimensions; index++) {
      vector[index] = index / 1000.0f;
    }
    return vector;
  }

  private String embeddingResponse(float[] embedding) throws JsonProcessingException {
    return objectMapper.writeValueAsString(
        Map.of(
            "object",
            "list",
            "model",
            "text-embedding-3-small",
            "data",
            List.of(Map.of("object", "embedding", "index", 0, "embedding", embedding))));
  }
}

package com.openat.search.product.infrastructure.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class InferenceServerImageClientTest {

  private static final String BASE_URL = "http://inference.test/v1";

  private MockRestServiceServer server;
  private InferenceServerImageClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client =
        new InferenceServerImageClient(
            builder.build(), BASE_URL + "/", "test-api-key", "gpt-5.4-nano");
  }

  @Test
  void analyzesMultipartImageThroughOpenAiResponsesContract() {
    server
        .expect(requestTo(BASE_URL + "/responses"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.model").value("gpt-5.4-nano"))
        .andExpect(jsonPath("$.instructions").isNotEmpty())
        .andExpect(jsonPath("$.input[0].role").value("user"))
        .andExpect(jsonPath("$.input[0].content[0].type").value("input_text"))
        .andExpect(jsonPath("$.input[0].content[0].text").value("추가 검색 조건: 빨간색 상품"))
        .andExpect(jsonPath("$.input[0].content[1].type").value("input_image"))
        .andExpect(jsonPath("$.input[0].content[1].image_url").value("data:image/png;base64,AQID"))
        .andRespond(
            withSuccess(
                """
                {
                  "id": "resp_test",
                  "object": "response",
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {"type": "output_text", "text": "빨간색 원형 상품 흰색 배경"}
                      ]
                    }
                  ]
                }
                """,
                MediaType.APPLICATION_JSON));

    MockMultipartFile image =
        new MockMultipartFile("image", "product.png", "image/png", new byte[] {1, 2, 3});

    assertThat(client.analyzeImage(image, "  빨간색 상품  ")).isEqualTo("빨간색 원형 상품 흰색 배경");
    server.verify();
  }

  @Test
  void rejectsResponseWithoutOutputText() {
    server
        .expect(requestTo(BASE_URL + "/responses"))
        .andRespond(withSuccess("{\"output\":[]}", MediaType.APPLICATION_JSON));

    MockMultipartFile image =
        new MockMultipartFile("image", "product.png", "image/png", new byte[] {1});

    assertThatThrownBy(() -> client.analyzeImage(image, null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("returned no text");
    server.verify();
  }
}

package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class InferenceLlmClientTest {

  private static final String BASE_URL = "http://inference-service";
  private MockRestServiceServer server;
  private InferenceLlmClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new InferenceLlmClient(builder.build(), "test-key", "configured-model");
  }

  @Test
  @DisplayName("완료 요청은 설정 모델·인증 헤더와 함께 프롬프트를 전달하고 응답 텍스트를 반환한다")
  void complete_sendsConfiguredModelAndReturnsContent() {
    server
        .expect(requestTo(BASE_URL + "/chat/completions"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(
            content()
                .json(
                    "{\"model\":\"configured-model\",\"response_format\":{\"type\":\"json_object\"}}"))
        .andRespond(
            withSuccess(
                """
                {"choices":[{"message":{"role":"assistant","content":"{\\"sections\\":[]}"}}]}
                """,
                MediaType.APPLICATION_JSON));

    String result = client.complete("prompt");

    assertThat(result).isEqualTo("{\"sections\":[]}");
    server.verify();
  }

  @Test
  @DisplayName("응답에 choices가 없으면 예외를 던진다")
  void complete_whenResponseHasNoChoices_throws() {
    server
        .expect(requestTo(BASE_URL + "/chat/completions"))
        .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client.complete("prompt")).isInstanceOf(RestClientException.class);
  }
}

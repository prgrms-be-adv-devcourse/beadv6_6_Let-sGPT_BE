package com.openat.recommendation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/** RestClient 빈들이 커넥션 풀을 쪼개지 않는지, 그러면서 서비스별 타임아웃은 지키는지 못 박는다. */
class RecommendationClientConfigTest {

  private static final Duration INTERNAL_TIMEOUT = Duration.ofMillis(700);
  private static final Duration SEARCH_TIMEOUT = Duration.ofMillis(1500);
  private static final Duration INFERENCE_TIMEOUT = Duration.ofSeconds(8);

  private final RecommendationClientConfig config = new RecommendationClientConfig();

  @Test
  @DisplayName("RestClient 빈 5개가 HttpClient 하나를 공유한다")
  void allRestClients_shareASingleHttpClient() {
    List<HttpClient> httpClients =
        requestFactories().stream()
            .map(factory -> (HttpClient) ReflectionTestUtils.getField(factory, "httpClient"))
            .distinct()
            .toList();

    assertThat(httpClients).hasSize(1);
  }

  @Test
  @DisplayName("HttpClient를 공유해도 서비스별 읽기 타임아웃은 그대로 남는다")
  void perServiceReadTimeouts_areNotLostBySharing() {
    assertThat(requestFactories())
        .extracting(factory -> ReflectionTestUtils.getField(factory, "readTimeout"))
        .containsExactly(
            INTERNAL_TIMEOUT,
            INTERNAL_TIMEOUT,
            INTERNAL_TIMEOUT,
            SEARCH_TIMEOUT,
            INFERENCE_TIMEOUT);
  }

  private List<JdkClientHttpRequestFactory> requestFactories() {
    return Stream.of(
            config.orderRestClient(RestClient.builder(), "http://order", INTERNAL_TIMEOUT),
            config.memberRestClient(RestClient.builder(), "http://member", INTERNAL_TIMEOUT),
            config.productRestClient(RestClient.builder(), "http://product", INTERNAL_TIMEOUT),
            config.searchRestClient(RestClient.builder(), "http://search", SEARCH_TIMEOUT),
            config.inferenceRestClient(RestClient.builder(), "http://llm", INFERENCE_TIMEOUT))
        .map(RecommendationClientConfigTest::requestFactory)
        .toList();
  }

  private static JdkClientHttpRequestFactory requestFactory(RestClient restClient) {
    Object factory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
    assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
    return (JdkClientHttpRequestFactory) factory;
  }
}

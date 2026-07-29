package com.openat.recommendation.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RecommendationClientConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

  /**
   * RestClient 빈 5개가 하나의 {@link HttpClient}를 공유한다. 빈마다 새로 만들면 셀렉터 스레드와
   * 커넥션 풀이 5개로 쪼개져, 같은 서비스로 가는 커넥션도 재사용되지 못한다.
   *
   * <p>서비스별로 다른 읽기 타임아웃은 그대로 유지된다 — {@code JdkClientHttpRequestFactory}는
   * 읽기 타임아웃을 요청마다 적용하고(HttpClient에 설정하지 않는다), HttpClient 수준 설정인 연결
   * 타임아웃은 원래부터 5개가 모두 같은 값이었다.
   */
  private final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @Bean
  RestClient orderRestClient(
      RestClient.Builder builder,
      @Value("${services.order.url}") String baseUrl,
      @Value("${services.internal.timeout}") Duration timeout) {
    return restClient(builder, baseUrl, timeout);
  }

  @Bean
  RestClient memberRestClient(
      RestClient.Builder builder,
      @Value("${services.member.url}") String baseUrl,
      @Value("${services.internal.timeout}") Duration timeout) {
    return restClient(builder, baseUrl, timeout);
  }

  @Bean
  RestClient productRestClient(
      RestClient.Builder builder,
      @Value("${services.product.url}") String baseUrl,
      @Value("${services.internal.timeout}") Duration timeout) {
    return restClient(builder, baseUrl, timeout);
  }

  @Bean
  RestClient searchRestClient(
      RestClient.Builder builder,
      @Value("${services.search.url}") String baseUrl,
      @Value("${services.search.timeout}") Duration timeout) {
    return restClient(builder, baseUrl, timeout);
  }

  @Bean
  RestClient inferenceRestClient(
      RestClient.Builder builder,
      @Value("${inference.base-url}") String baseUrl,
      @Value("${inference.timeout}") Duration timeout) {
    return restClient(builder, baseUrl, timeout);
  }

  private RestClient restClient(RestClient.Builder builder, String baseUrl, Duration readTimeout) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}

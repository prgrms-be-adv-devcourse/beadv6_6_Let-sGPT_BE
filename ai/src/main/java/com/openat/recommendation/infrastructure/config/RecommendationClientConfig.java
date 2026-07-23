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
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}

package com.openat.order.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ProductClientConfig {

  private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

  @Bean
  public RestClient productRestClient(
      RestClient.Builder restClientBuilder,
      @Value("${services.product.url}") String productBaseUrl) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECTION_TIMEOUT).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(READ_TIMEOUT);

    return restClientBuilder.baseUrl(productBaseUrl).requestFactory(requestFactory).build();
  }
}

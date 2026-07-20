package com.openat.order.infrastructure.config;

import com.openat.order.infrastructure.client.RetrySleeper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentClientConfig {

  private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

  @Bean
  public RestClient paymentRestClient(
      RestClient.Builder restClientBuilder,
      @Value("${services.payment.url}") String paymentBaseUrl) {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECTION_TIMEOUT).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return restClientBuilder.baseUrl(paymentBaseUrl).requestFactory(requestFactory).build();
  }

  @Bean
  public RetrySleeper retrySleeper() {
    return Thread::sleep;
  }
}

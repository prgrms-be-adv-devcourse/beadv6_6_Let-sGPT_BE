package com.openat.chat.infrastructure.websearch;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WebSearchInfrastructureConfig {

  @Bean("tavilyRestClient")
  RestClient tavilyRestClient(TavilyProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.getReadTimeout());
    RestClient.Builder builder =
        RestClient.builder().baseUrl("https://api.tavily.com").requestFactory(requestFactory);
    if (properties.isConfigured()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());
    }
    return builder.build();
  }
}

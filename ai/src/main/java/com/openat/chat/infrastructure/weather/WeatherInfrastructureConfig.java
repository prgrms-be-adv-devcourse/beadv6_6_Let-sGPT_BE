package com.openat.chat.infrastructure.weather;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OpenMeteoProperties.class)
public class WeatherInfrastructureConfig {

  static final String FORECAST_BASE_URL = "https://api.open-meteo.com";

  @Bean
  @Qualifier("openMeteoForecastRestClient")
  RestClient openMeteoForecastRestClient(OpenMeteoProperties properties) {
    return restClient(FORECAST_BASE_URL, properties);
  }

  private RestClient restClient(String baseUrl, OpenMeteoProperties properties) {
    HttpClient client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.getConnectTimeout())
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
    requestFactory.setReadTimeout(properties.getReadTimeout());
    return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
  }
}

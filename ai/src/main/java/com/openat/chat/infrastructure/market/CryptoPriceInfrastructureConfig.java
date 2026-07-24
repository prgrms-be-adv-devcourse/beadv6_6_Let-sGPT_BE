package com.openat.chat.infrastructure.market;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(CoinGeckoProperties.class)
public class CryptoPriceInfrastructureConfig {

  static final String BASE_URL = "https://api.coingecko.com/api/v3";

  @Bean("coinGeckoRestClient")
  RestClient coinGeckoRestClient(CoinGeckoProperties properties) {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
    requestFactory.setReadTimeout(properties.getReadTimeout());
    return RestClient.builder().baseUrl(BASE_URL).requestFactory(requestFactory).build();
  }
}

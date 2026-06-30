package com.openat.payment.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// X-3 — 주문 서비스 내부 API 호출용 RestClient. real 프로필에서만 등록.
@Configuration
@Profile("real")
public class OrderClientConfig {

    @Bean
    public RestClient orderRestClient(@Value("${services.order.url}") String orderBaseUrl,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(2));

        return RestClient.builder()
                .baseUrl(orderBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}

package com.openat.payment.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// X-3 — 주문 서비스 내부 API 호출용 RestClient. 전 프로필 공통 등록.
@Configuration
public class OrderClientConfig {

    // WS-E(7/10 observability plan) — 정적 RestClient.builder()는 auto-configured 계측(전파 헤더 +
    // span 기록)이 붙지 않는다. Boot가 만든 RestClient.Builder 빈을 주입받아 커스터마이즈해야 트레이스가
    // 이어진다(research §4 실사 발견).
    @Bean
    public RestClient orderRestClient(
            RestClient.Builder builder,
            @Value("${services.order.url}") String orderBaseUrl,
            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(2));

        return builder.baseUrl(orderBaseUrl).requestFactory(requestFactory).build();
    }
}

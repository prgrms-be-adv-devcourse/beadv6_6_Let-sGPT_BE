package com.openat.settlement.infrastructure.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// payment의 정산 대사 일별 API 호출용 RestClient(WS-3) — payment 모듈의 OrderClientConfig와 동일 형태.
// Boot 자동설정 RestClient.Builder 빈은 spring-boot-restclient 모듈(Boot 4.x에서 분리됨) 의존성이
// 있어야 등록된다 — 없으면 기동 자체가 실패한다(7-15 research, 정적 팩토리 우회 조치는 철회).
@Configuration
public class PaymentReconciliationClientConfig {

    @Bean
    public RestClient paymentReconciliationRestClient(
            RestClient.Builder builder, @Value("${services.payment.url}") String paymentBaseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        return builder.baseUrl(paymentBaseUrl).requestFactory(requestFactory).build();
    }
}

package com.openat.settlement.infrastructure.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// payment의 정산 대사 일별 API 호출용 RestClient(WS-3) — payment 모듈의 OrderClientConfig와 형태는 비슷하지만,
// 거기서 쓰는 Boot 자동설정 RestClient.Builder 빈은 @Profile("real")에서만 실행되어 검증된 적이 없었다.
// 실기동 검증 결과 settlement에서는 그 빈이 없어 기동 자체가 실패해서(APPLICATION FAILED TO START),
// 정적 팩토리 RestClient.builder()로 직접 생성하도록 바꿨다 — 트레이스 전파 계측은 없지만 우선 동작을 확보.
@Configuration
public class PaymentReconciliationClientConfig {

    @Bean
    public RestClient paymentReconciliationRestClient(@Value("${services.payment.url}") String paymentBaseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder().baseUrl(paymentBaseUrl).requestFactory(requestFactory).build();
    }
}

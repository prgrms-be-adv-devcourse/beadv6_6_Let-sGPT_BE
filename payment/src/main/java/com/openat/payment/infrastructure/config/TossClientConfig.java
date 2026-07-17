package com.openat.payment.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// G2 — 실제 토스페이먼츠 API 호출용 RestClient. 전 프로필 공통 등록.
@Configuration
public class TossClientConfig {

    private static final String TOSS_BASE_URL = "https://api.tosspayments.com";

    // I2-1 — confirm 메인 경로가 3~5초 안에 응답을 못 받으면 즉시 예외로 빠지고, 그 PENDING row 확정은
    // 웹훅(I1)+TTL스캐너에 위임한다(여기서 설정하는 타임아웃이 그 즉시타임아웃의 실제 구현체).
    // WS-E(7/10 observability plan) — 정적 RestClient.builder()는 auto-configured 계측(전파 헤더 +
    // span 기록)이 붙지 않는다. Boot가 만든 RestClient.Builder 빈을 주입받아 커스터마이즈해야 트레이스가
    // 이어진다(research §4 실사 발견).
    @Bean
    public RestClient tossRestClient(
            RestClient.Builder builder, @Value("${pg.secret-key}") String secretKey, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        String basicAuth = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());

        return builder.baseUrl(TOSS_BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .build();
    }
}

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

    // I2-1 — confirm 메인 경로가 3~5초 안에 응답을 못 받으면 즉시 예외로 빠지고, 그 PENDING row 확정은
    // 웹훅(I1)+TTL스캐너에 위임한다(여기서 설정하는 타임아웃이 그 즉시타임아웃의 실제 구현체).
    // WS-E(7/10 observability plan) — 정적 RestClient.builder()는 auto-configured 계측(전파 헤더 +
    // span 기록)이 붙지 않는다. Boot가 만든 RestClient.Builder 빈을 주입받아 커스터마이즈해야 트레이스가
    // 이어진다(research §4 실사 발견).
    @Bean
    public RestClient tossRestClient(
            RestClient.Builder builder,
            @Value("${pg.secret-key}") String secretKey,
            @Value("${pg.base-url:https://api.tosspayments.com}") String baseUrl,
            ObjectMapper objectMapper) {
        // HTTP/1.1 고정. JDK HttpClient 기본값(HTTP_2)은 평문 대상(부하테스트 WireMock)에 h2c 업그레이드를
        // 시도하다 스트림이 깨진다(RST_STREAM·빈 body → ResourceAccessException 누적 → 토스 서킷 고착).
        // 실 토스는 confirm 유량이 리미터로 10건/초 캡이라 1.1 커넥션 풀로 충분하다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        String basicAuth = Base64.getEncoder().encodeToString((secretKey + ":").getBytes());

        return builder.baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
                .build();
    }
}

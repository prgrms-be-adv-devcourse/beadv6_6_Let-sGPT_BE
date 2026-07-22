package com.openat.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.common.auth.UserHeaders;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RateLimiterKeyResolverConfigTest {

  private final KeyResolver userIdKeyResolver =
      new RateLimiterKeyResolverConfig().userIdKeyResolver();

  @Test
  @DisplayName("사용자별 제한 키는 외부 헤더가 아니라 검증된 JWT subject를 사용한다")
  void userIdKeyResolver_authenticated_usesJwtSubject() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/payments/confirm")
                .header(UserHeaders.USER_ID, "spoofed-user")
                .header("X-Forwarded-For", "203.0.113.10")
                .build());
    JwtAuthenticationToken authentication =
        new JwtAuthenticationToken(
            Jwt.withTokenValue("access-token")
                .header("alg", "none")
                .subject("verified-user")
                .build(),
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    String key =
        userIdKeyResolver
            .resolve(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
            .block(Duration.ofSeconds(1));

    assertThat(key).isEqualTo("verified-user");
  }

  @Test
  @DisplayName("JWT 인증 정보가 없으면 사용자별 제한 키를 클라이언트 IP로 대체한다")
  void userIdKeyResolver_unauthenticated_fallsBackToClientIp() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/payments/confirm")
                .header(UserHeaders.USER_ID, "spoofed-user")
                .header("X-Forwarded-For", "203.0.113.10, 10.0.0.1")
                .build());

    String key = userIdKeyResolver.resolve(exchange).block(Duration.ofSeconds(1));

    assertThat(key).isEqualTo("203.0.113.10");
  }
}

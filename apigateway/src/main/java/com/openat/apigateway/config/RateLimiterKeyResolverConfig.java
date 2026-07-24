package com.openat.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 결제 민감 라우트용 RedisRateLimiter KeyResolver 빈.
 *
 * <p>{@code #{@userIdKeyResolver}} / {@code #{@ipKeyResolver}}로 라우트별 참조한다.
 *
 * <p>빈 키 금지: SCG는 빈 키를 거부(deny-empty-key)하므로 두 리졸버 모두 항상 비어있지 않은 키를 돌려준다. confirm 계열은 검증된 JWT
 * subject로 제한하고, JWT 인증 정보가 없으면 IP로 폴백한다. 웹훅은 애초에 인증이 없어 IP 키만 쓴다.
 */
@Configuration
public class RateLimiterKeyResolverConfig {

  // KeyResolver 빈이 둘이라 RequestRateLimiter 필터의 기본 KeyResolver 주입이 모호해진다 —
  // 라우트가 key-resolver를 명시하지 않을 때 쓰이는 기본값으로 이 빈을 지정(@Primary).
  @Primary
  @Bean
  public KeyResolver userIdKeyResolver() {
    return exchange ->
        ReactiveSecurityContextHolder.getContext()
            .mapNotNull(SecurityContext::getAuthentication)
            .filter(Authentication::isAuthenticated)
            .filter(JwtAuthenticationToken.class::isInstance)
            .cast(JwtAuthenticationToken.class)
            .mapNotNull(authentication -> authentication.getToken().getSubject())
            .filter(subject -> !subject.isBlank())
            .switchIfEmpty(Mono.fromSupplier(() -> clientIp(exchange)));
  }

  @Bean
  public KeyResolver ipKeyResolver() {
    return exchange -> Mono.just(clientIp(exchange));
  }

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  private String clientIp(ServerWebExchange exchange) {
    // 프록시/LB 뒤에 있으면 X-Forwarded-For의 첫 홉이 실제 클라이언트 IP.
    String forwardedFor = exchange.getRequest().getHeaders().getFirst(X_FORWARDED_FOR);
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    if (exchange.getRequest().getRemoteAddress() != null) {
      return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
    return "unknown";
  }
}

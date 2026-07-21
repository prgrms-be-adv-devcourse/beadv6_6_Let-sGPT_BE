package com.openat.apigateway.config;

import com.openat.common.auth.UserHeaders;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 결제 민감 라우트용 RedisRateLimiter KeyResolver 빈.
 *
 * <p>{@code #{@userIdKeyResolver}} / {@code #{@ipKeyResolver}}로 라우트별 참조한다.
 *
 * <p>빈 키 금지: SCG는 빈 키를 거부(deny-empty-key)하므로 두 리졸버 모두 항상 비어있지 않은 키를 돌려준다.
 * confirm 계열은 유저별({@code X-User-Id}, {@code UserContextRelayFilter}가 주입)로 제한하되, 헤더가
 * 없으면(비인증 등) IP로 폴백해 절대 빈 키를 반환하지 않는다. 웹훅은 애초에 인증이 없어 IP 키만 쓴다.
 */
@Configuration
public class RateLimiterKeyResolverConfig {

  // KeyResolver 빈이 둘이라 RequestRateLimiter 필터의 기본 KeyResolver 주입이 모호해진다 —
  // 라우트가 key-resolver를 명시하지 않을 때 쓰이는 기본값으로 이 빈을 지정(@Primary).
  @Primary
  @Bean
  public KeyResolver userIdKeyResolver() {
    return exchange -> {
      String userId = exchange.getRequest().getHeaders().getFirst(UserHeaders.USER_ID);
      if (userId != null && !userId.isBlank()) {
        return Mono.just(userId);
      }
      return Mono.just(clientIp(exchange));
    };
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

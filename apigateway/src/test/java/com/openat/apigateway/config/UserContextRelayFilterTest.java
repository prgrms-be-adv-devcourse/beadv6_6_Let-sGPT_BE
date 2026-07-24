package com.openat.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import com.openat.common.auth.UserHeaders;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class UserContextRelayFilterTest {

  private final UserContextRelayFilter filter = new UserContextRelayFilter();

  @Test
  @DisplayName("외부 위조 사용자 헤더는 검증된 access token의 subject와 권한으로 교체한다")
  void filter_accessToken_replacesSpoofedContextHeaders() {
    MockServerWebExchange exchange =
        exchangeWithSpoofedHeaders("spoofed-user", "ROLE_ADMIN", "spoofed-seller");
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    GatewayFilterChain chain = capturedChain(forwarded);
    JwtAuthenticationToken authentication = accessToken("verified-admin", "ROLE_ADMIN");

    filter
        .filter(exchange, chain)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
        .block(Duration.ofSeconds(1));

    HttpHeaders headers = forwarded.get().getRequest().getHeaders();
    assertThat(headers.getFirst(UserHeaders.USER_ID)).isEqualTo("verified-admin");
    assertThat(headers.getFirst(UserHeaders.USER_ROLES)).isEqualTo("ROLE_ADMIN");
    assertThat(headers.getFirst(UserHeaders.SELLER_ID)).isNull();
  }

  @Test
  @DisplayName("사용자 컨텍스트를 첫 번째 라우트 필터보다 먼저 검증된 값으로 교체한다")
  void filter_runsBeforeFirstRouteFilter() {
    MockServerWebExchange exchange =
        exchangeWithSpoofedHeaders("spoofed-user", "ROLE_ADMIN", "spoofed-seller");
    AtomicReference<String> observedUserId = new AtomicReference<>();
    GatewayFilter routeFilter =
        (candidate, chain) -> {
          observedUserId.set(candidate.getRequest().getHeaders().getFirst(UserHeaders.USER_ID));
          return chain.filter(candidate);
        };
    Route route =
        Route.async()
            .id("context-relay-order-test")
            .uri("http://localhost")
            .predicate(candidate -> true)
            .filters(new OrderedGatewayFilter(routeFilter, 1))
            .build();
    exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
    JwtAuthenticationToken authentication = accessToken("verified-admin", "ROLE_ADMIN");

    new FilteringWebHandler(List.of(filter), false)
        .handle(exchange)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
        .block(Duration.ofSeconds(1));

    assertThat(observedUserId).hasValue("verified-admin");
  }

  @Test
  @DisplayName("인증 정보가 없으면 외부에서 주입한 모든 사용자 컨텍스트 헤더를 제거한다")
  void filter_unauthenticated_stripsAllContextHeaders() {
    MockServerWebExchange exchange =
        exchangeWithSpoofedHeaders("spoofed-user", "ROLE_ADMIN", "spoofed-seller");
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

    filter.filter(exchange, capturedChain(forwarded)).block(Duration.ofSeconds(1));

    HttpHeaders headers = forwarded.get().getRequest().getHeaders();
    assertThat(headers.getFirst(UserHeaders.USER_ID)).isNull();
    assertThat(headers.getFirst(UserHeaders.USER_ROLES)).isNull();
    assertThat(headers.getFirst(UserHeaders.SELLER_ID)).isNull();
  }

  private MockServerWebExchange exchangeWithSpoofedHeaders(
      String userId, String roles, String sellerId) {
    return MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/ai/chats/capabilities")
            .header(UserHeaders.USER_ID, userId)
            .header(UserHeaders.USER_ROLES, roles)
            .header(UserHeaders.SELLER_ID, sellerId)
            .build());
  }

  private GatewayFilterChain capturedChain(AtomicReference<ServerWebExchange> forwarded) {
    return candidate -> {
      forwarded.set(candidate);
      return Mono.empty();
    };
  }

  private JwtAuthenticationToken accessToken(String subject, String authority) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("access-token").header("alg", "none").subject(subject).build(),
        List.of(new SimpleGrantedAuthority(authority)));
  }
}

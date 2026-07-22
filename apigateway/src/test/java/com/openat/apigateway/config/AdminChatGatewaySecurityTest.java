package com.openat.apigateway.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(AdminChatGatewaySecurityTest.ChatEndpoint.class)
@Import({
  SecurityConfig.class,
  ApiErrorResponseWriter.class,
  AdminChatGatewaySecurityTest.ChatEndpoint.class
})
class AdminChatGatewaySecurityTest {

  @Autowired WebTestClient webTestClient;

  @MockitoBean ReactiveJwtDecoder jwtDecoder;

  @MockitoBean RouteLocator routeLocator;

  @BeforeEach
  void setUpRoute() {
    Route route = mock(Route.class);
    given(route.getPredicate()).willReturn(exchange -> Mono.just(true));
    given(routeLocator.getRoutes()).willReturn(Flux.just(route));
  }

  @Test
  @DisplayName("인증 정보가 없으면 관리자 챗봇 경로를 거부한다")
  void request_unauthenticated_returnsUnauthorized() {
    webTestClient
        .get()
        .uri("/api/v1/ai/chats/capabilities")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  @DisplayName("일반 사용자는 관리자 챗봇 경로에 접근할 수 없다")
  void request_userRole_returnsForbidden() {
    given(jwtDecoder.decode("user-token")).willReturn(Mono.just(jwt("user-token", "USER")));

    webTestClient
        .get()
        .uri("/api/v1/ai/chats/capabilities")
        .headers(headers -> headers.setBearerAuth("user-token"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("관리자는 관리자 챗봇 경로에 접근할 수 있다")
  void request_adminRole_returnsOk() {
    given(jwtDecoder.decode("admin-token")).willReturn(Mono.just(jwt("admin-token", "ADMIN")));

    webTestClient
        .get()
        .uri("/api/v1/ai/chats/capabilities")
        .headers(headers -> headers.setBearerAuth("admin-token"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private Jwt jwt(String tokenValue, String role) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "none")
        .subject("member-id")
        .claim("roles", java.util.List.of(role))
        .build();
  }

  @RestController
  public static class ChatEndpoint {

    @GetMapping("/api/v1/ai/chats/capabilities")
    public String capabilities() {
      return HttpStatus.OK.getReasonPhrase();
    }
  }
}

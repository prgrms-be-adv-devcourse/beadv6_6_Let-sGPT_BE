package com.openat.apigateway.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(CategoryGatewaySecurityTest.CategoryEndpoint.class)
@Import({
  SecurityConfig.class,
  ApiErrorResponseWriter.class,
  CategoryGatewaySecurityTest.CategoryEndpoint.class
})
class CategoryGatewaySecurityTest {

  private static final String CATEGORY_ID = "00000000-0000-0000-0000-000000000000";

  @Autowired WebTestClient webTestClient;

  @MockitoBean ReactiveJwtDecoder jwtDecoder;

  @MockitoBean RouteLocator routeLocator;

  // ConcurrencyLimitFilter도 WebFilter라 @WebFluxTest 슬라이스 스캔에 함께 포함된다(actuator
  // 자동설정은 슬라이스 테스트에 없어 MeterRegistry 빈이 없으면 컨텍스트 기동 자체가 실패한다) -
  // 실제 게이트/카운터 동작을 검증하는 테스트가 아니므로 그냥 실제 SimpleMeterRegistry로 채운다.
  @org.springframework.boot.test.context.TestConfiguration
  static class MeterRegistryTestConfig {
    @org.springframework.context.annotation.Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @BeforeEach
  void setUpRoute() {
    Route route = mock(Route.class);
    given(route.getPredicate()).willReturn(exchange -> Mono.just(true));
    given(routeLocator.getRoutes()).willReturn(Flux.just(route));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/categories", "/product/api/v1/categories"})
  @DisplayName("카테고리 조회는 인증 없이 할 수 있다")
  void categoryRead_unauthenticated_returnsOk(String uri) {
    webTestClient.get().uri(uri).exchange().expectStatus().isOk();
  }

  @ParameterizedTest
  @MethodSource("categoryWriteRequests")
  @DisplayName("일반 사용자는 카테고리 관리 요청을 할 수 없다")
  void categoryWrite_userRole_returnsForbidden(HttpMethod method, String uri) {
    given(jwtDecoder.decode("user-token")).willReturn(Mono.just(jwt("user-token", "USER")));

    webTestClient
        .method(method)
        .uri(uri)
        .headers(headers -> headers.setBearerAuth("user-token"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @ParameterizedTest
  @MethodSource("categoryWriteRequests")
  @DisplayName("관리자는 카테고리 관리 요청을 할 수 있다")
  void categoryWrite_adminRole_returnsOk(HttpMethod method, String uri) {
    given(jwtDecoder.decode("admin-token")).willReturn(Mono.just(jwt("admin-token", "ADMIN")));

    webTestClient
        .method(method)
        .uri(uri)
        .headers(headers -> headers.setBearerAuth("admin-token"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private static Stream<Arguments> categoryWriteRequests() {
    return Stream.of(
        Arguments.of(HttpMethod.POST, "/api/v1/categories"),
        Arguments.of(HttpMethod.PATCH, "/api/v1/categories/" + CATEGORY_ID),
        Arguments.of(HttpMethod.DELETE, "/api/v1/categories/" + CATEGORY_ID),
        Arguments.of(HttpMethod.POST, "/product/api/v1/categories"),
        Arguments.of(HttpMethod.PATCH, "/product/api/v1/categories/" + CATEGORY_ID),
        Arguments.of(HttpMethod.DELETE, "/product/api/v1/categories/" + CATEGORY_ID));
  }

  private Jwt jwt(String tokenValue, String role) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "none")
        .subject("member-id")
        .claim("roles", List.of(role))
        .build();
  }

  @RestController
  static class CategoryEndpoint {

    @GetMapping({"/api/v1/categories", "/product/api/v1/categories"})
    String getAll() {
      return "OK";
    }

    @PostMapping({"/api/v1/categories", "/product/api/v1/categories"})
    String create() {
      return "OK";
    }

    @PatchMapping({
      "/api/v1/categories/{id}",
      "/product/api/v1/categories/{id}"
    })
    String update() {
      return "OK";
    }

    @DeleteMapping({
      "/api/v1/categories/{id}",
      "/product/api/v1/categories/{id}"
    })
    String delete() {
      return "OK";
    }
  }
}

package com.openat.apigateway.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A안(scoped 토큰 audience 확장) 회귀 테스트 — 게이트웨이 쪽 절반.
 *
 * <p>{@code GET /api/v1/settlements/seller/**}가 {@code hasRole("SELLER")}에서
 * {@code scopedFor("openat-settlement")}로 바뀐 뒤에도, 관리자 경로(hasRole("ADMIN"))가
 * 영향을 받지 않는지, 그리고 이전에는 통과하던 일반 SELLER access 토큰이 이제는
 * 차단되는지(= parksunkyu의 컨트롤러 변경과 짝을 이루는 회귀점) 확인한다.
 *
 * <p>settlement 컨트롤러의 {@code sellerId} 파라미터 제거·{@code X-Seller-Id} 사용은
 * settlement 모듈 담당(parksunkyu)의 몫이라 이 테스트에는 포함하지 않는다.
 */
@WebFluxTest(SettlementSellerGatewaySecurityTest.SettlementEndpoint.class)
@Import({
  SecurityConfig.class,
  ApiErrorResponseWriter.class,
  SettlementSellerGatewaySecurityTest.SettlementEndpoint.class
})
class SettlementSellerGatewaySecurityTest {

  private static final String SELLER_INFO_ID = "00000000-0000-0000-0000-0000000000aa";

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
  @DisplayName("토큰 없이 정산 판매자 조회를 요청하면 401이다")
  void sellerSettlement_noToken_returnsUnauthorized() {
    webTestClient
        .get()
        .uri("/api/v1/settlements/seller/orders")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  @DisplayName("일반 회원(ROLE_USER)은 정산 판매자 조회를 할 수 없다")
  void sellerSettlement_userRole_returnsForbidden() {
    given(jwtDecoder.decode("user-token")).willReturn(Mono.just(accessJwt("user-token", "USER")));

    webTestClient
        .get()
        .uri("/api/v1/settlements/seller/orders")
        .headers(headers -> headers.setBearerAuth("user-token"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("회귀 규약: 일반 SELLER access 토큰은 더 이상 정산 판매자 조회를 통과하지 못한다")
  void sellerSettlement_sellerAccessToken_noLongerPasses() {
    given(jwtDecoder.decode("seller-access-token"))
        .willReturn(Mono.just(accessJwt("seller-access-token", "SELLER")));

    webTestClient
        .get()
        .uri("/api/v1/settlements/seller/orders")
        .headers(headers -> headers.setBearerAuth("seller-access-token"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("aud=openat-settlement scoped 토큰은 정산 판매자 조회를 통과한다")
  void sellerSettlement_settlementScopedToken_returnsOk() {
    given(jwtDecoder.decode("settlement-scoped-token"))
        .willReturn(Mono.just(scopedJwt("settlement-scoped-token", "openat-settlement")));

    webTestClient
        .get()
        .uri("/api/v1/settlements/seller/orders")
        .headers(headers -> headers.setBearerAuth("settlement-scoped-token"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  @DisplayName("aud=openat-product scoped 토큰은 정산 판매자 조회를 통과하지 못한다 (audience 교차 사용 차단)")
  void sellerSettlement_productScopedToken_returnsForbidden() {
    given(jwtDecoder.decode("product-scoped-token"))
        .willReturn(Mono.just(scopedJwt("product-scoped-token", "openat-product")));

    webTestClient
        .get()
        .uri("/api/v1/settlements/seller/orders")
        .headers(headers -> headers.setBearerAuth("product-scoped-token"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("scoped 토큰(roles 클레임 없음)은 관리자 정산 경로를 통과하지 못한다")
  void adminSettlement_settlementScopedToken_returnsForbidden() {
    given(jwtDecoder.decode("settlement-scoped-token"))
        .willReturn(Mono.just(scopedJwt("settlement-scoped-token", "openat-settlement")));

    webTestClient
        .get()
        .uri("/api/v1/settlements/admin/orders")
        .headers(headers -> headers.setBearerAuth("settlement-scoped-token"))
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("ROLE_ADMIN access 토큰은 관리자 정산 경로를 통과한다 (이번 변경의 영향을 받지 않음)")
  void adminSettlement_adminAccessToken_returnsOk() {
    given(jwtDecoder.decode("admin-token")).willReturn(Mono.just(accessJwt("admin-token", "ADMIN")));

    webTestClient
        .get()
        .uri("/api/v1/settlements/admin/orders")
        .headers(headers -> headers.setBearerAuth("admin-token"))
        .exchange()
        .expectStatus()
        .isOk();
  }

  private Jwt accessJwt(String tokenValue, String role) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "none")
        .subject("member-id")
        .claim("typ", "access")
        .claim("roles", List.of(role))
        .build();
  }

  /** RFC 8693 delegation 모델: sub=sellerInfoId, aud=지정 audience, roles 클레임 없음. */
  private Jwt scopedJwt(String tokenValue, String audience) {
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "none")
        .subject(SELLER_INFO_ID)
        .claim("typ", "scoped")
        .claim("aud", List.of(audience))
        .claim("act", Map.of("sub", "member-id"))
        .build();
  }

  @RestController
  static class SettlementEndpoint {

    @GetMapping("/api/v1/settlements/seller/orders")
    String sellerOrders() {
      return "OK";
    }

    @GetMapping("/api/v1/settlements/admin/orders")
    String adminOrders() {
      return "OK";
    }
  }
}

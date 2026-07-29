package com.openat.apigateway.config;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

@WebFluxTest(SettlementGatewaySecurityTest.SettlementEndpoint.class)
@Import({
        SecurityConfig.class,
        ApiErrorResponseWriter.class,
        SettlementGatewaySecurityTest.SettlementEndpoint.class
})
class SettlementGatewaySecurityTest {

    private static final String URI = "/api/v1/settlements/seller/orders?page=0&size=10";

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    RouteLocator routeLocator;

    @BeforeEach
    void setUpRoute() {
        Route route = mock(Route.class);
        given(route.getPredicate()).willReturn(exchange -> Mono.just(true));
        given(routeLocator.getRoutes()).willReturn(Flux.just(route));
    }

    @Test
    void settlementScopedTokenWithReadScope_isAllowed() {
        given(jwtDecoder.decode("settlement-token")).willReturn(Mono.just(
                scopedJwt("settlement-token", "openat-settlement", "settlement:read")));

        webTestClient.get()
                .uri(URI)
                .headers(headers -> headers.setBearerAuth("settlement-token"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void productScopedToken_isForbidden() {
        given(jwtDecoder.decode("product-token")).willReturn(Mono.just(
                scopedJwt("product-token", "openat-product", "product:write")));

        webTestClient.get()
                .uri(URI)
                .headers(headers -> headers.setBearerAuth("product-token"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void settlementTokenWithoutReadScope_isForbidden() {
        given(jwtDecoder.decode("wrong-scope-token")).willReturn(Mono.just(
                scopedJwt("wrong-scope-token", "openat-settlement", "product:write")));

        webTestClient.get()
                .uri(URI)
                .headers(headers -> headers.setBearerAuth("wrong-scope-token"))
                .exchange()
                .expectStatus().isForbidden();
    }

    private Jwt scopedJwt(String tokenValue, String audience, String scope) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "none")
                .subject("seller-info-id")
                .claim("typ", "scoped")
                .claim("aud", List.of(audience))
                .claim("scope", scope)
                .build();
    }

    @RestController
    static class SettlementEndpoint {

        @GetMapping("/api/v1/settlements/seller/orders")
        String getOrders() {
            return "OK";
        }
    }
}

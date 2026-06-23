package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import com.openat.common.error.CommonErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 요청 경로가 실제로 매칭되는 라우트가 있는지부터 확인한다.
 *
 * <p>원래대로(이 필터 없이)는 {@code anyExchange().authenticated()} 때문에 토큰 없이
 * 미등록 경로를 호출해도 라우팅 단계까지 가보지 못하고 401이 먼저 나서, "이 경로가
 * 애초에 존재하는지"를 알 방법이 없었다. 이 필터가 라우트 매칭을 먼저 해보고, 매칭되는
 * 라우트가 하나도 없으면 인증 여부와 무관하게 즉시 404로 응답한다.
 *
 * <p>swagger-ui/api-docs/webjars처럼 게이트웨이 자신이 직접 서빙하는(별도 Route로
 * 등록되지 않은) 경로는 라우트 매칭 대상이 아니므로 미리 통과시킨다.
 *
 * <p><b>Spring Security 체인 안에 {@code addFilterBefore(this, SecurityWebFiltersOrder.FIRST)}로
 * 끼워넣지 않고 일반 전역 {@code WebFilter}로 등록한 이유</b>: {@code authorizeExchange()}의
 * {@code anyExchange().authenticated()}(명시적 {@code pathMatchers(...)}에 안 걸리는 catch-all)에
 * 해당하는 요청은 Spring Boot가 {@code spring.security.oauth2.resourceserver.jwt.*} 설정만
 * 보고 자동 등록하는 별도의 기본 {@code SecurityWebFilterChain}(빈 이름:
 * {@code reactiveJwtSecurityFilterChain})으로 처리되어, {@code addFilterBefore}로 끼워넣은
 * 필터를 건너뛰는 현상이 실제로 확인됐다(우리 체인에 {@code @Order(HIGHEST_PRECEDENCE)}를
 * 줘도 해당 catch-all 케이스는 여전히 우회됨). 전역 {@code WebFilter}는 어느 보안 체인이
 * 선택되는지와 무관하게 WebFlux 디스패치 전체를 감싸므로 이 문제가 없다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RouteExistenceFilter implements WebFilter {

    private static final List<String> LOCALLY_HANDLED_PATTERNS = List.of(
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**"
    );

    private final RouteLocator routeLocator;
    private final ApiErrorResponseWriter responseWriter;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isLocallyHandled(path)) {
            return chain.filter(exchange);
        }

        return routeLocator.getRoutes()
                .concatMap(route -> Mono.from(route.getPredicate().apply(exchange)))
                .any(Boolean::booleanValue)
                .flatMap(matched -> Boolean.TRUE.equals(matched)
                        ? chain.filter(exchange)
                        : responseWriter.write(exchange, HttpStatus.NOT_FOUND, CommonErrorCode.NOT_FOUND));
    }

    private boolean isLocallyHandled(String path) {
        return LOCALLY_HANDLED_PATTERNS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }
}

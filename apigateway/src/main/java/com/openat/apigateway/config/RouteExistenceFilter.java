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
 * 원래는 anyExchange().authenticated() 때문에 미등록 경로를 호출해도 401이 발생
 *
**/

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RouteExistenceFilter implements WebFilter {

    private static final List<String> LOCALLY_HANDLED_PATTERNS = List.of(
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**",
            // k8s readiness/liveness probe + Prometheus scrape — 게이트웨이 자체 로컬 엔드포인트라
            // 프록시 라우트로 등록돼 있지 않음. 여기 빠지면 라우트 미존재로 간주돼 404로 막힘.
            "/actuator/**"
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

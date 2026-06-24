package com.openat.apigateway.config;

import com.openat.common.auth.UserHeaders;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
public class UserContextRelayFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(auth -> {
                    String userId = auth.getToken().getSubject();
                    String roles = auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .collect(Collectors.joining(","));
                    return mutateWithUserHeaders(exchange, userId == null ? "" : userId, roles);
                })
                // 클라이언트가 직접 보낸 헤더를 신뢰해서는 안됨. 항상 헤더 제거
                .defaultIfEmpty(stripUserHeaders(exchange))
                .flatMap(chain::filter);
    }

    private ServerWebExchange mutateWithUserHeaders(ServerWebExchange exchange, String userId, String roles) {
        return exchange.mutate()
                .request(builder -> builder
                        .headers(this::removeUserHeaders)
                        .header(UserHeaders.USER_ID, userId)
                        .header(UserHeaders.USER_ROLES, roles))
                .build();
    }

    private ServerWebExchange stripUserHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(builder -> builder.headers(this::removeUserHeaders))
                .build();
    }

    private void removeUserHeaders(HttpHeaders headers) {
        headers.remove(UserHeaders.USER_ID);
        headers.remove(UserHeaders.USER_ROLES);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

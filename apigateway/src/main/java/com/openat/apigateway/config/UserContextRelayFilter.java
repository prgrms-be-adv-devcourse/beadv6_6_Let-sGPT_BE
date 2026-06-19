package com.openat.apigateway.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
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
                    return exchange.mutate()
                            .request(builder -> builder
                                    .header("X-User-Id", userId == null ? "" : userId)
                                    .header("X-User-Roles", roles))
                            .build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

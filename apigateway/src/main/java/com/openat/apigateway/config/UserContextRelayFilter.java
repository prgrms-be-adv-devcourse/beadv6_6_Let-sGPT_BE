package com.openat.apigateway.config;

import com.openat.common.auth.UserHeaders;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * JWT 검증 결과를 다운스트림 헤더로 중계한다.
 *
 * <p>access 토큰: {@code X-User-Id}(memberId) + {@code X-User-Roles} 주입. 기존 동작 불변.
 *
 * <p>scoped 토큰 (RFC 8693 delegation, {@code typ=scoped}):
 * <ul>
 *   <li>{@code X-Seller-Id} = {@code sub}(sellerInfoId) — 테넌트 헤더</li>
 *   <li>{@code X-User-Id} / {@code X-User-Roles}는 주입하지 않고 strip
 *       ({@code sub}=sellerInfoId가 {@code X-User-Id}로 오염되는 것 방지)</li>
 *   <li>위임 감사: {@code act.sub}(memberId) + {@code sub}(sellerInfoId)를 INFO 로그</li>
 * </ul>
 *
 * <p>안티-스푸핑: 토큰 유무와 관계없이 클라이언트가 보낸
 * {@code X-User-Id}/{@code X-User-Roles}/{@code X-Seller-Id}를 항상 제거한다.
 */
@Component
public class UserContextRelayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(UserContextRelayFilter.class);

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_ACT = "act";
    private static final String TYPE_SCOPED = "scoped";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(auth -> {
                    String typ = auth.getToken().getClaimAsString(CLAIM_TYPE);
                    if (TYPE_SCOPED.equals(typ)) {
                        return mutateWithScopedHeaders(exchange, auth);
                    } else {
                        return mutateWithUserHeaders(exchange, auth);
                    }
                })
                // 클라이언트가 직접 보낸 헤더를 신뢰해서는 안됨. 항상 헤더 제거
                .defaultIfEmpty(stripAllContextHeaders(exchange))
                .flatMap(chain::filter);
    }

    /** access 토큰: X-User-Id(memberId) + X-User-Roles 주입 */
    private ServerWebExchange mutateWithUserHeaders(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        String userId = auth.getToken().getSubject();
        String roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        return exchange.mutate()
                .request(builder -> builder
                        .headers(this::removeAllContextHeaders)
                        .header(UserHeaders.USER_ID, userId == null ? "" : userId)
                        .header(UserHeaders.USER_ROLES, roles))
                .build();
    }

    /**
     * scoped 토큰 (RFC 8693 delegation): X-Seller-Id(sub=sellerInfoId)만 주입.
     * X-User-Id/X-User-Roles는 주입하지 않음(sub=sellerInfoId가 X-User-Id로 오염 방지).
     * act.sub(memberId) + sub(sellerInfoId)를 감사 로그에 기록.
     */
    @SuppressWarnings("unchecked")
    private ServerWebExchange mutateWithScopedHeaders(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        String sellerInfoId = auth.getToken().getSubject();
        // act 클레임: Map<String, Object> { "sub": memberId }
        Object actClaim = auth.getToken().getClaim(CLAIM_ACT);
        String memberId = null;
        if (actClaim instanceof Map<?, ?> actMap) {
            Object actSub = actMap.get("sub");
            if (actSub != null) {
                memberId = actSub.toString();
            }
        }

        // 위임 감사 로그 (토큰이 암호학적으로 검증된 지점)
        String path = exchange.getRequest().getPath().value();
        log.info("[delegation-audit] actor={} seller={} path={}", memberId, sellerInfoId, path);

        final String finalSellerInfoId = sellerInfoId == null ? "" : sellerInfoId;
        return exchange.mutate()
                .request(builder -> builder
                        .headers(this::removeAllContextHeaders)
                        .header(UserHeaders.SELLER_ID, finalSellerInfoId))
                .build();
    }

    private ServerWebExchange stripAllContextHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(builder -> builder.headers(this::removeAllContextHeaders))
                .build();
    }

    private void removeAllContextHeaders(HttpHeaders headers) {
        headers.remove(UserHeaders.USER_ID);
        headers.remove(UserHeaders.USER_ROLES);
        headers.remove(UserHeaders.SELLER_ID);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

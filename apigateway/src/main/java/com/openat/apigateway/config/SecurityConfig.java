package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import com.openat.common.error.CommonErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import reactor.core.publisher.Flux;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final ApiErrorResponseWriter responseWriter;

    public SecurityConfig(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        // 인증 실패(토큰 없음/만료/위조) → common 공통 에러 포맷 401
                        .authenticationEntryPoint(authenticationEntryPoint())
                        // 인가 실패(역할 불충족) → common 공통 에러 포맷 403
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeExchange(exchange -> exchange

                        // 게이트웨이 호스팅 통합 Swagger UI
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/webjars/**").permitAll()

                        // 각 서비스 OpenAPI 문서 + 개별 Swagger UI
                        .pathMatchers(
                                "/*/v3/api-docs/**",
                                "/*/swagger-ui/**",
                                "/*/swagger-ui.html").permitAll()

                        // member 공개 기능 (로그인/토큰 발급, JWKS)
                        .pathMatchers(
                                "/member/api/v1/auth/**",
                                "/member/oauth2/jwks").permitAll()

                        // POST 만 공개
                        .pathMatchers(
                                HttpMethod.POST, "/member/api/v1/members").permitAll()

                        // 판매자만 접근 가능
                        .pathMatchers(
                                "/member/api/v1/seller/**").hasRole("SELLER")

                        // 인증만 되면 누구나 접근 가능 (경로 생략 가능)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) ->
                responseWriter.write(exchange, HttpStatus.UNAUTHORIZED, CommonErrorCode.UNAUTHENTICATED);
    }

    private ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, ex) ->
                responseWriter.write(exchange, HttpStatus.FORBIDDEN, CommonErrorCode.FORBIDDEN);
    }

    private ReactiveJwtAuthenticationConverter jwtAuthenticationConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            List<GrantedAuthority> authorities = (roles == null ? List.<String>of() : roles).stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            return Flux.fromIterable(authorities);
        });
        return converter;
    }
}
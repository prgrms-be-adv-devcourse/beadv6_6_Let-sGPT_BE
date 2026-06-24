package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import com.openat.common.error.CommonErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Flux;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final ApiErrorResponseWriter responseWriter;

    public SecurityConfig(ApiErrorResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    /**
     * Spring Boot가 {@code spring.security.oauth2.resourceserver.jwt.*} 설정만 보고
     * "reactiveJwtSecurityFilterChain"이라는 기본 체인을 추가로 자동 등록하는데, 이 체인은
     * CORS/permitAll 목록 등 우리가 정의한 규칙을 전혀 모른 채 그냥 "인증됐는지"만 본다.
     * {@code SecurityWebFilterChain}이 여러 개일 때 Spring Security는 {@code @Order}가
     * 낮은(우선순위 높은) 체인의 매처가 매칭되면 그 체인을 쓰는데, 명시적 순서를 안 주면
     * 우리 체인이 항상 이긴다는 보장이 없어 일부 경로가 자동 체인으로 새서 permitAll이어야
     * 할 경로가 보호되는 등 어긋났다. 항상 우리 체인이 먼저 선택되도록 최우선순위를 명시한다.
     * (라우트 존재 여부 체크는 더 이상 이 체인에 끼워넣지 않는다 — {@link RouteExistenceFilter} 참고.)
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource
    ) {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
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
                        // 기본값 "/v3/api-docs"가 아닌 override 사용한 것도 작성
                        .pathMatchers(
                                "/*/api-docs/**",
                                "/*/swagger-ui/**",
                                "/*/swagger-ui.html").permitAll()

                        // member 공개 기능 (JWKS)
                        .pathMatchers("/auth/jwks").permitAll()

                        // payment 웹훅 — Toss PG가 JWT 없이 직접 호출 (apigateway/docs/SECURITY_CONFIG_GUIDE.md 패턴)
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/payments/webhook",
                                "/api/v1/wallet/charge/webhook",
                                "/api/v1/refunds/webhook").permitAll()

                        // POST만 공개
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/members",
                                "/api/v1/members/login",
                                "/api/v1/members/refresh").permitAll()

                        // 판매자 등록 — 아직 ROLE_USER인 회원도 최초 등록 가능
                        .pathMatchers(HttpMethod.POST, "/api/v1/seller/me").authenticated()

                        // 본인 판매자 정보 조회·수정·삭제 — 이미 판매자인 경우만
                        .pathMatchers(HttpMethod.GET, "/api/v1/seller/me").hasRole("SELLER")
                        .pathMatchers("/api/v1/seller/me/**").hasRole("SELLER")

                        // 관리자 전용: 판매자 정보 UUID 단건 조회
                        .pathMatchers(HttpMethod.GET, "/api/v1/seller/*").hasRole("ADMIN")

                        // 그 외 seller 경로 (확장 대비)
                        .pathMatchers("/api/v1/seller/**").authenticated()

//                        // 판매자만
//                        .pathMatchers(
//                                /** 엔드포인트 작성 **/
//                        ).hasRole("SELLER")
//                        // 관리자만
//                        .pathMatchers(
//                                /** 엔드포인트 작성 **/
//                        ).hasRole("ADMIN")
//                        // 관리자 또는 판매자
//                        .pathMatchers(
//                                /** 엔드포인트 작성 **/
//                        ).hasAnyRole("ADMIN", "SELLER")

                        // 인증만 되면 누구나
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * swagger-ui(localhost:8000)에서 각 서비스로 직접 "Try it out" 호출할 때 발생하는
     * CORS 문제를 막기 위함. 로컬 개발 전용이라 localhost의 모든 포트를 허용 패턴으로 열어둔다
     * (운영 배포 시에는 실제 프론트 도메인으로 좁혀야 함).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "https://localhost:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
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
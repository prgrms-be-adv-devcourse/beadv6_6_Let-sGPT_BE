package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import com.openat.common.error.CommonErrorCode;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Flux;

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

                        // member 공개 기능 (JWKS, RFC 8693 STS)
                        .pathMatchers("/auth/jwks", "/auth/token").permitAll()

                        // payment 웹훅 — Toss PG가 JWT 없이 직접 호출 (apigateway/docs/SECURITY_CONFIG_GUIDE.md 패턴)
                        // payment 라우트가 Path=/payment/**+StripPrefix=1(product/order/settlement와 동일 컨벤션,
                        // member만 예외)이라 Security 필터가 보는 실제 요청 경로도 /payment 접두사가 붙어야 매칭된다.
                        .pathMatchers(
                                HttpMethod.POST,
                                "/payment/api/v1/payments/webhook",
                                "/payment/api/v1/wallet/charge/webhook",
                                "/payment/api/v1/refunds/webhook").permitAll()

                        // POST만 공개
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/members",
                                "/api/v1/members/login",
                                "/api/v1/members/refresh").permitAll()

                        // 판매자 등록 — 아직 ROLE_USER인 회원도 최초 등록 가능.
                        // ※ .authenticated()는 scoped 토큰도 통과시키므로 반드시 access()로 대체
                        .pathMatchers(HttpMethod.POST, "/api/v1/seller/me").access(authenticatedAndNotScoped())

                        // 본인 판매자 정보 조회·수정·삭제 — 이미 판매자인 경우만
                        // hasRole()은 roles 클레임 없는 scoped 토큰을 이미 거부하므로 안전
                        .pathMatchers(HttpMethod.GET, "/api/v1/seller/me").hasRole("SELLER")
                        .pathMatchers("/api/v1/seller/me/**").hasRole("SELLER")

                        // 관리자 전용: userId로 해당 회원의 판매자 정보 전체 조회
                        .pathMatchers(HttpMethod.GET, "/api/v1/seller/*").hasRole("ADMIN")

                        // 그 외 seller 경로 (확장 대비) — scoped 토큰 명시적 거부
                        .pathMatchers("/api/v1/seller/**").access(authenticatedAndNotScoped())

                        // 정산 관리자 전용
                        .pathMatchers(HttpMethod.GET, "/api/v1/settlements/admin/*").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.GET, "/api/v1/settlements/seller/*").hasRole("SELLER")

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

                        // -----------------------------------------------------------------
                        // RFC 8693: product 판매자 write 경로 — scoped 토큰(typ=scoped) 전용
                        // access 토큰 사용 시 거부, 반대로 scoped 토큰을 다른 경로에 쓰면 거부.
                        // -----------------------------------------------------------------

                        // product 카탈로그 읽기 — 공개 (/product 내부 컨벤션 + /api/v1 FE 컨벤션)
                        .pathMatchers(HttpMethod.GET, "/product/**").permitAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/api/v1/products/**",
                                "/api/v1/drops/**",
                                "/api/v1/categories/**").permitAll()

                        // product 판매자 write — scoped 토큰(typ=scoped, aud=openat-product)만 허용 (GET은 위에서 공개)
                        .pathMatchers("/product/products", "/product/products/**").access(scopedFor("openat-product"))
                        .pathMatchers(
                                "/api/v1/products", "/api/v1/products/**",
                                "/api/v1/drops", "/api/v1/drops/**").access(scopedFor("openat-product"))

                        // 그 외 모든 경로: 인증 필요 + scoped 토큰 명시적 거부
                        .anyExchange().access(authenticatedAndNotScoped())
                )
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

    /**
     * scoped 토큰(typ=scoped)을 명시적으로 거부하는 공통 인가 관리자.
     * JWT 인증이 됐더라도 scoped 토큰은 product write 경로 외에서 진입할 수 없어야 한다.
     * 역할 없는 순수 {@code .authenticated()} 경로에서도 scoped 토큰이 새는 것을 방지한다.
     */
    private ReactiveAuthorizationManager<AuthorizationContext> authenticatedAndNotScoped() {
        return (authentication, context) ->
                authentication.<AuthorizationResult>map(auth -> new AuthorizationDecision(
                        auth instanceof JwtAuthenticationToken jwtAuth
                        && !"scoped".equals(jwtAuth.getToken().getClaimAsString("typ"))
                )).defaultIfEmpty(new AuthorizationDecision(false));
    }

    /**
     * scoped 토큰(typ=scoped)이고 지정 audience를 포함하는 경우만 허용하는 인가 관리자.
     * product write처럼 특정 서비스 전용 scoped 토큰이 필요한 경로에 사용한다.
     */
    private ReactiveAuthorizationManager<AuthorizationContext> scopedFor(String audience) {
        return (authentication, context) ->
                authentication.<AuthorizationResult>map(auth -> new AuthorizationDecision(
                        auth instanceof JwtAuthenticationToken jwtAuth
                        && "scoped".equals(jwtAuth.getToken().getClaimAsString("typ"))
                        && jwtAuth.getToken().getAudience() != null
                        && jwtAuth.getToken().getAudience().contains(audience)
                )).defaultIfEmpty(new AuthorizationDecision(false));
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

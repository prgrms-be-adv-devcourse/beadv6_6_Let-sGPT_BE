package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import com.openat.common.error.CommonErrorCode;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
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
     * Public search requests must bypass resource-server authentication completely.
     *
     * <p>{@code permitAll()} skips authorization only. An expired Bearer token saved by Swagger
     * can otherwise be rejected with 401 before the authorization rule is evaluated.</p>
     */
    @Bean
    @Order(0)
    public SecurityWebFilterChain publicSearchSecurityWebFilterChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource
    ) {
        http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/searchs/**"))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll());
        return http.build();
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
    @Order(1)
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

                        // k8s readiness/liveness probe + Prometheus scrape — 클러스터 내부 전용
                        // (Ingress는 /와 /api만 라우팅하므로 외부에 노출되지 않는 경로)
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()

                        // payment 웹훅 — Toss PG가 JWT 없이 직접 호출 (apigateway/docs/SECURITY_CONFIG_GUIDE.md 패턴).
                        // 서명검증은 없고(AbstractPgWebhookHandler, 2026-06-24 제거) PG 재조회가 진실 공급원이라
                        // 인증을 방어선으로 삼지 않는 설계 — 임의 호출자가 때려도 재조회 단계에서 걸러진다.
                        //
                        // Security(WebFilterChainProxy, order=-100)는 WebFilter라 라우트 선택·StripPrefix보다
                        // 항상 먼저 실행된다 → 여기서 매칭되는 건 "클라이언트가 보낸 원본 경로"다.
                        // payment로 가는 경로가 두 갈래라 양쪽 다 등록한다:
                        //  1) /api/v1/... — payments-api/wallet-api/refunds-api 라우트(StripPrefix 없음).
                        //     Ingress가 외부에 노출하는 경로(/api)라 Toss 웹훅이 실제로 타는 경로.
                        //     Toss 개발자센터 등록 URL: https://openat.duckdns.org/api/v1/payments/webhook 등.
                        //  2) /payment/... — payment 라우트(Path=/payment/**+StripPrefix=1, compose 프로필 한정).
                        //     Ingress에 /payment rule이 없어 외부 도달은 불가하나, 클러스터 내부 호출과
                        //     향후 /payment 노출 대비로 유지한다.
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/payments/webhook",
                                "/api/v1/wallet/charge/webhook",
                                "/api/v1/refunds/webhook").permitAll()
                        .pathMatchers(
                                HttpMethod.POST,
                                "/payment/api/v1/payments/webhook",
                                "/payment/api/v1/wallet/charge/webhook",
                                "/payment/api/v1/refunds/webhook").permitAll()

                        // payment 내부 운영 경로(DLQ 재처리, PG 대사 실행, 정산 이벤트 테스트, 정산 조회) —
                        // payment 서비스 자체 SecurityConfig가 permitAll 전면 개방이라 게이트웨이 인가가 유일한 방어선.
                        // /payment/** 라우트(StripPrefix=1)로 /payment/internal/... 이 서비스의 /internal/... 로 전달된다.
                        .pathMatchers("/payment/internal/**").hasRole("ADMIN")

                        // POST만 공개
                        // /restore: 탈퇴 유예기간 복구 — login과 동일하게 호출 시점엔 미인증 상태라
                        // permitAll 필요. 서비스 자체가 email/password로 재인증하므로 안전(§MemberService.restore).
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/members",
                                "/api/v1/members/login",
                                "/api/v1/members/refresh",
                                "/api/v1/members/restore").permitAll()

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
                        //
                        // /* 가 아니라 /** 다 — /* 는 단일 세그먼트만 매칭해서 하위 경로가 생기면
                        // 이 규칙을 벗어나 anyExchange().access(authenticatedAndNotScoped())로 샌다.
                        // /settlement/api/v1/settlements/admin/** 도 함께 막는다 — 게이트웨이에
                        // settlement로 가는 라우트가 두 벌이라(settlement-api: /api/v1/settlements/**
                        // 그대로 프록시, settlement: /settlement/** + StripPrefix=1) 스트립 후 같은
                        // :9140 컨트롤러에 도달한다. 아래 seller 규칙과 같은 이유.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/settlements/admin/**",
                                "/settlement/api/v1/settlements/admin/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/settlements/admin/**",
                                "/settlement/api/v1/settlements/admin/**").hasRole("ADMIN")

                        // 정산 판매자 조회 — scoped 토큰(typ=scoped, aud=openat-settlement)만 허용.
                        // access 토큰(ROLE_SELLER)은 더 이상 통과하지 못한다 — settlement 쪽 IDOR(다른 판매자
                        // sellerId를 파라미터로 넘겨 조회) 수정과 짝이다. scoped 토큰의 sub(sellerInfoId)를
                        // X-Seller-Id로 내려주면 settlement가 그 값을 신뢰해 본인 것만 조회하도록 바뀐다.
                        // scoped 토큰엔 roles 클레임이 없어 admin 경로로는 새지 않는다.
                        //
                        // /settlement/api/v1/settlements/seller/** 도 함께 막는다 — 안 막으면
                        // /settlement/** + StripPrefix=1 라우트로 우회해 이 규칙 자체를 안 타고
                        // anyExchange().access(authenticatedAndNotScoped())로 샌다. 그 경로는
                        // scoped 토큰만 거부할 뿐 access 토큰은 role 검사 없이 통과시키므로,
                        // ROLE_SELLER조차 없는 아무 로그인 회원이나 이 우회로로 정산을 조회할 수
                        // 있었다 — 원래 막으려던 구멍보다 넓은 구멍이었다.
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/settlements/seller/**",
                                "/settlement/api/v1/settlements/seller/**")
                        .access(scopedFor("openat-settlement", "settlement:read"))

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

                        // 카테고리 관리는 ADMIN 전용 (/product 라우트 우회 경로도 동일하게 제한)
                        .pathMatchers(
                                HttpMethod.POST,
                                "/api/v1/categories", "/api/v1/categories/**",
                                "/product/api/v1/categories", "/product/api/v1/categories/**").hasRole("ADMIN")
                        .pathMatchers(
                                HttpMethod.PATCH,
                                "/api/v1/categories", "/api/v1/categories/**",
                                "/product/api/v1/categories", "/product/api/v1/categories/**").hasRole("ADMIN")
                        .pathMatchers(
                                HttpMethod.DELETE,
                                "/api/v1/categories", "/api/v1/categories/**",
                                "/product/api/v1/categories", "/product/api/v1/categories/**").hasRole("ADMIN")

                        // product 판매자 write — scoped 토큰(typ=scoped, aud=openat-product)만 허용 (GET은 위에서 공개)
                        .pathMatchers("/product/products", "/product/products/**").access(scopedFor("openat-product"))
                        .pathMatchers(
                                "/api/v1/products", "/api/v1/products/**",
                                "/api/v1/drops", "/api/v1/drops/**").access(scopedFor("openat-product"))

                        // 개인화 추천 읽기 — 공개 (비회원도 기본 추천 조회 가능)
                        .pathMatchers(HttpMethod.GET, "/api/v1/recommendations").permitAll()

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
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);

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

    /**
     * scoped 토큰의 type, audience와 필수 scope를 모두 검증한다.
     */
    private ReactiveAuthorizationManager<AuthorizationContext> scopedFor(
            String audience,
            String requiredScope
    ) {
        return (authentication, context) ->
                authentication.<AuthorizationResult>map(auth -> new AuthorizationDecision(
                        auth instanceof JwtAuthenticationToken jwtAuth
                        && "scoped".equals(jwtAuth.getToken().getClaimAsString("typ"))
                        && jwtAuth.getToken().getAudience() != null
                        && jwtAuth.getToken().getAudience().contains(audience)
                        && containsScope(jwtAuth.getToken().getClaimAsString("scope"), requiredScope)
                )).defaultIfEmpty(new AuthorizationDecision(false));
    }

    private boolean containsScope(String scopeClaim, String requiredScope) {
        if (scopeClaim == null || scopeClaim.isBlank()) {
            return false;
        }
        return List.of(scopeClaim.trim().split("\\s+")).contains(requiredScope);
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

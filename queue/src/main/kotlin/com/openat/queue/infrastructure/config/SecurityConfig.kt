package com.openat.queue.infrastructure.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * WebFlux+SSE 전환분: MVC의 `SecurityFilterChain`/`HttpSecurity`는 서블릿 전제라 WebFlux
 * 애플리케이션에서는 적용되지 않는다(`spring.main.web-application-type: reactive`로 전환됨).
 * 의도는 예전과 동일하다 - 인증은 apigateway가 단일 지점에서 검증하고, 이 서비스는 게이트웨이가
 * 주입한 헤더(X-User-Id 등)를 신뢰하며 자체 로그인/인가는 하지 않는다. `SecurityWebFilterChain`/
 * `ServerHttpSecurity`가 그 리액티브 대응이다. 세션 미사용(stateless)은 WebFlux 기본값이라
 * MVC 쪽의 `sessionCreationPolicy` 설정에 대응하는 별도 지정이 필요 없다.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeExchange { it.anyExchange().permitAll() }
            .build()
}

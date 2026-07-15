package com.openat.queue.infrastructure.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * 인증은 apigateway가 단일 지점에서 검증하고 이 서비스는 게이트웨이가 주입한
 * 헤더(X-User-Id 등)를 신뢰한다(다른 도메인 서비스와 동일 모델). 그래서 이 서비스는
 * 자체 로그인/인가를 하지 않고 spring-security-starter가 강제하는 기본 잠금만 해제한다.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(anyRequest, permitAll)
            }
        }
        return http.build()
    }
}

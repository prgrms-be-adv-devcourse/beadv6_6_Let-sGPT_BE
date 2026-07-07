package com.openat.search.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 인증/인가는 apigateway가 JWT로 끝낸 뒤 X-User-Id/X-User-Roles 헤더로 신뢰해서 넘겨준다 ({@code
 * common.auth.UserContextFilter} 참고). 이 서비스가 직접 인증을 다시 수행하지 않으므로, spring-boot-starter-security가
 * 기본으로 켜는 로그인 폼/Basic Auth를 모두 끄고 전부 permitAll 처리한다. (이 설정이 없으면 Spring Security 기본 자동설정 때문에 모든 요청이
 * 401로 막힌다.)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(AbstractHttpConfigurer::disable)
        .csrf(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}

package com.openat.member.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc 기본 설정만으로는 swagger-ui에 "Authorize" 버튼이 뜨지 않아 토큰이 필요한
 * API("/me" 등)를 Try it out으로 테스트할 방법이 없었다. HTTP Bearer 시큐리티 스킴을 등록해서
 * (로그인으로 받은 accessToken을 "Authorize" 버튼에 넣으면 이후 모든 요청에
 * {@code Authorization: Bearer <token>} 헤더가 자동으로 붙는다).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI memberOpenAPI() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(BEARER_SCHEME_NAME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME));
    }
}

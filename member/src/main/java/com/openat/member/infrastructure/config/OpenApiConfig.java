package com.openat.member.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc Swagger 설정.
 *
 * <p>servers를 명시하지 않으면 Swagger UI가 현재 접속한 host를 기준 URL로 자동 사용한다.
 *
 * <h3>접속 방식별 용도 구분</h3>
 * <ul>
 *   <li><b>{@code localhost:8000/swagger-ui} — 게이트웨이 경유 (정상 인증 흐름)</b><br>
 *       API 요청이 게이트웨이를 통해 나간다. Authorize 버튼에 로그인 응답의 accessToken을 입력하면
 *       게이트웨이가 JWT를 검증하고 X-User-Id/X-User-Roles 헤더를 붙여 서비스에 전달한다.</li>
 *   <li><b>{@code localhost:9100/swagger-ui} — 서비스 직접 접속 (개발·단위 테스트용)</b><br>
 *       인증 컨텍스트({@code @CurrentUser})가 필요 없는 엔드포인트나 서비스 로직만 검증할 때 사용한다.
 *       인증이 필요한 엔드포인트는 게이트웨이(8000)를 통해 테스트한다.</li>
 * </ul>
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

package com.openat.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  // TODO: 게이트웨이/JWT 인증 연동 시 APIKEY 헤더 스킴 -> HTTP bearer(JWT)로 교체
  private static final String USER_ID_HEADER_SCHEME = "userIdHeader";

  @Bean
  public OpenAPI productOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Product API")
                .description("상품 도메인 API 문서")
                .version("v1"))
        .addSecurityItem(new SecurityRequirement().addList(USER_ID_HEADER_SCHEME))
        .components(
            new Components()
                .addSecuritySchemes(
                    USER_ID_HEADER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-User-Id")
                        .description("게이트웨이가 인증 후 전달하는 사용자 식별자 (회원 도메인 연동 전 테스트용)")));
  }
}

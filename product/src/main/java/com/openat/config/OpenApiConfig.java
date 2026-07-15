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

  private static final String BEARER_SCHEME_NAME = "bearerAuth";

  @Bean
  public OpenAPI productOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Product API").description("상품 도메인 API 문서").version("v1"))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME_NAME,
                    new SecurityScheme()
                        .name(BEARER_SCHEME_NAME)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "member에서 발급받은 활성 스토어 범위 판매자 JWT. 게이트웨이가 검증 후 X-Seller-Id를 주입한다.")));
  }
}

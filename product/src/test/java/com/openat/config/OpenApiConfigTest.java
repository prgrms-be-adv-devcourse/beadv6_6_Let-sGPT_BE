package com.openat.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("상품 OpenAPI 설정")
class OpenApiConfigTest {

  @Test
  @DisplayName("판매자 scoped JWT를 Bearer 인증으로 노출한다")
  void productOpenApi_validConfig_exposesBearerAuthentication() {
    // given
    OpenApiConfig config = new OpenApiConfig();

    // when
    OpenAPI openAPI = config.productOpenApi();

    // then
    SecurityScheme bearerAuth = openAPI.getComponents().getSecuritySchemes().get("bearerAuth");
    assertThat(bearerAuth.getType()).isEqualTo(SecurityScheme.Type.HTTP);
    assertThat(bearerAuth.getScheme()).isEqualTo("bearer");
    assertThat(bearerAuth.getBearerFormat()).isEqualTo("JWT");
    assertThat(openAPI.getSecurity())
        .singleElement()
        .satisfies(it -> assertThat(it).containsKey("bearerAuth"));
  }
}

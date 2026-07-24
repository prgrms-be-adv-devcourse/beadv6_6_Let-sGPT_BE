package com.openat.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class AdminChatGatewayRouteTest {

  @ParameterizedTest
  @ValueSource(strings = {"application-local.yaml", "application-compose.yaml"})
  @DisplayName("로컬과 compose 프로필 모두 관리자 챗봇 경로를 변경 없이 전달한다")
  void load_adminChatRoute_preservesApiPath(String resourceName) throws IOException {
    // given
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    ClassPathResource resource = new ClassPathResource(resourceName);

    // when
    List<PropertySource<?>> propertySources = loader.load(resourceName, resource);
    PropertySource<?> properties = propertySources.getFirst();

    // then
    assertThat(properties.getProperty("spring.cloud.gateway.server.webflux.routes[0].id"))
        .isEqualTo("admin-chat-api");
    assertThat(
            properties.getProperty("spring.cloud.gateway.server.webflux.routes[0].predicates[0]"))
        .isEqualTo("Path=/api/v1/ai/chats,/api/v1/ai/chats/**");
    assertThat(properties.getProperty("spring.cloud.gateway.server.webflux.routes[0].filters"))
        .isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "application-local.yaml,http://localhost:9160,http://localhost:9160/api-docs",
    "application-compose.yaml,http://ai:9160,http://ai:9160/api-docs"
  })
  @DisplayName("로컬과 compose 프로필에서 AI OpenAPI 문서를 라우팅하고 통합 문서에 집계한다")
  void load_aiOpenApiRouteAndAggregate_includesAiService(
      String resourceName, String serviceUri, String apiDocsUri) throws IOException {
    // given
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    ClassPathResource resource = new ClassPathResource(resourceName);

    // when
    List<PropertySource<?>> propertySources = loader.load(resourceName, resource);
    PropertySource<?> properties = propertySources.getFirst();
    int routeIndex = findRouteIndex(properties, "ai");

    // then
    assertThat(routeIndex).isNotNegative();
    assertThat(
            properties.getProperty(
                "spring.cloud.gateway.server.webflux.routes[" + routeIndex + "].uri"))
        .isEqualTo(serviceUri);
    assertThat(
            properties.getProperty(
                "spring.cloud.gateway.server.webflux.routes[" + routeIndex + "].predicates[0]"))
        .isEqualTo("Path=/ai/**");
    assertThat(
            properties.getProperty(
                "spring.cloud.gateway.server.webflux.routes[" + routeIndex + "].filters[0]"))
        .isEqualTo("StripPrefix=1");
    assertThat(properties.getProperty("openapi.aggregate.services.ai-service"))
        .isEqualTo(apiDocsUri);
  }

  private int findRouteIndex(PropertySource<?> properties, String routeId) {
    for (int index = 0; index < 100; index++) {
      Object id =
          properties.getProperty("spring.cloud.gateway.server.webflux.routes[" + index + "].id");
      if (routeId.equals(id)) {
        return index;
      }
      if (id == null) {
        return -1;
      }
    }
    return -1;
  }
}

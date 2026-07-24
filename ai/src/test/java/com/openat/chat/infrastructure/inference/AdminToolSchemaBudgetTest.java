package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.chat.application.port.AdminDataQueryPort;
import com.openat.chat.application.port.CryptoPricePort;
import com.openat.chat.application.port.WeatherPort;
import com.openat.chat.application.port.WebSearchPort;
import com.openat.chat.application.service.ExternalSearchPolicy;
import com.openat.chat.application.service.OperationContextRegistry;
import com.openat.chat.infrastructure.inference.tool.AdminDataTools;
import com.openat.chat.infrastructure.inference.tool.CryptoPriceTools;
import com.openat.chat.infrastructure.inference.tool.InternalDataSchemaSelector;
import com.openat.chat.infrastructure.inference.tool.OperationContextTools;
import com.openat.chat.infrastructure.inference.tool.WeatherTools;
import com.openat.chat.infrastructure.inference.tool.WebSearchTools;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

class AdminToolSchemaBudgetTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  @Test
  @DisplayName("운영 챗봇이 모델에 전달하는 도구 스키마 크기와 이름 중복을 감시한다")
  void productionToolSchemas_haveUniqueNamesAndReportBudget() {
    OperationContextRegistry operationContexts = new OperationContextRegistry();
    ToolCallback[] callbacks = productionToolCallbacks(operationContexts);
    AdminChatPromptFactory prompts = promptFactory(operationContexts);

    int definitionCharacters =
        Arrays.stream(callbacks)
            .mapToInt(
                callback ->
                    callback.getToolDefinition().name().length()
                        + callback.getToolDefinition().description().length()
                        + callback.getToolDefinition().inputSchema().length())
            .sum();
    int schemaCharacters =
        Arrays.stream(callbacks)
            .mapToInt(callback -> callback.getToolDefinition().inputSchema().length())
            .sum();
    int wireDefinitionCharacters =
        Arrays.stream(callbacks).mapToInt(this::wireDefinitionCharacters).sum();

    System.out.printf(
        "ADMIN_TOOL_SCHEMA_BUDGET|tools=%d|definitionChars=%d|schemaChars=%d|wireDefinitionChars=%d|systemPromptChars=%d|combinedChars=%d%n",
        callbacks.length,
        definitionCharacters,
        schemaCharacters,
        wireDefinitionCharacters,
        prompts.routingSystem().length(),
        wireDefinitionCharacters + prompts.routingSystem().length());
    Arrays.stream(callbacks)
        .forEach(
            callback ->
                System.out.printf(
                    "ADMIN_TOOL_SCHEMA|name=%s|definitionChars=%d|schemaChars=%d%n",
                    callback.getToolDefinition().name(),
                    callback.getToolDefinition().name().length()
                        + callback.getToolDefinition().description().length()
                        + callback.getToolDefinition().inputSchema().length(),
                    callback.getToolDefinition().inputSchema().length()));
    if ("true".equalsIgnoreCase(System.getenv("PRINT_ADMIN_TOOL_SCHEMAS"))) {
      Arrays.stream(callbacks)
          .forEach(
              callback ->
                  System.out.printf(
                      "ADMIN_TOOL_SCHEMA_JSON|name=%s|schema=%s%n",
                      callback.getToolDefinition().name(),
                      callback.getToolDefinition().inputSchema()));
    }

    assertThat(callbacks).hasSize(7);
    assertThat(
            Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()).toList())
        .containsExactlyInAnyOrder(
            "lookupOrder",
            "countExpiredPaymentPendingOrders",
            "getCryptoPrice",
            "loadInternalDataSchemas",
            "getOpenAtOperationsContext",
            "getWeatherForecast",
            "searchWeb");
    assertThat(
            new HashSet<>(
                Arrays.stream(callbacks)
                    .map(callback -> callback.getToolDefinition().name())
                    .toList()))
        .hasSize(callbacks.length);
    String weatherSchema =
        Arrays.stream(callbacks)
            .filter(callback -> callback.getToolDefinition().name().equals("getWeatherForecast"))
            .findFirst()
            .orElseThrow()
            .getToolDefinition()
            .inputSchema();
    assertThat(requiredProperties(weatherSchema))
        .containsExactlyInAnyOrder("location", "latitude", "longitude", "day");
    assertThat(wireDefinitionCharacters + prompts.routingSystem().length()).isLessThan(6_000);
  }

  private ToolCallback[] productionToolCallbacks(OperationContextRegistry operationContexts) {
    return ToolCallbacks.from(
        new AdminDataTools(mock(AdminDataQueryPort.class)),
        new CryptoPriceTools(mock(CryptoPricePort.class)),
        new InternalDataSchemaSelector(),
        new OperationContextTools(operationContexts),
        new WeatherTools(mock(WeatherPort.class)),
        new WebSearchTools(mock(WebSearchPort.class), new ExternalSearchPolicy()));
  }

  private AdminChatPromptFactory promptFactory(OperationContextRegistry operationContexts) {
    return new AdminChatPromptFactory(
        operationContexts,
        new ChatInferenceProperties(),
        JsonMapper.builder().findAndAddModules().build(),
        CLOCK);
  }

  private List<String> requiredProperties(String schemaJson) {
    try {
      Map<String, Object> schema =
          new ObjectMapper().readValue(schemaJson, new TypeReference<>() {});
      if (!(schema.get("required") instanceof List<?> required)) {
        throw new IllegalStateException("도구 JSON Schema의 required가 배열이 아니에요.");
      }
      return required.stream().map(String.class::cast).toList();
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("도구 JSON Schema를 읽지 못했어요.", exception);
    }
  }

  private int wireDefinitionCharacters(ToolCallback callback) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      Map<String, Object> schema =
          mapper.readValue(callback.getToolDefinition().inputSchema(), new TypeReference<>() {});
      return callback.getToolDefinition().name().length()
          + callback.getToolDefinition().description().length()
          + mapper.writeValueAsString(schema).length();
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("도구 JSON Schema를 계산하지 못했어요.", exception);
    }
  }
}

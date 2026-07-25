package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.port.AdminChatInferencePort.BindingStatus;
import com.openat.chat.application.port.AdminChatInferencePort.RoutingResponse;
import com.openat.chat.application.service.ExternalSearchPolicy;
import com.openat.chat.application.service.OperationContextRegistry;
import com.openat.chat.domain.query.InternalDataDomain;
import com.openat.chat.infrastructure.inference.tool.AdminDataTools;
import com.openat.chat.infrastructure.inference.tool.CryptoPriceTools;
import com.openat.chat.infrastructure.inference.tool.InternalDataSchemaSelector;
import com.openat.chat.infrastructure.inference.tool.InternalQuerySubmissionTools;
import com.openat.chat.infrastructure.inference.tool.OperationContextTools;
import com.openat.chat.infrastructure.inference.tool.WeatherTools;
import com.openat.chat.infrastructure.inference.tool.WebSearchTools;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Spring AI 관리자 챗봇 수동 추론 어댑터")
class SpringAiAdminChatInferenceAdapterTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  private ChatModel chatModel;
  private ExecutorService executor;
  private SpringAiAdminChatInferenceAdapter adapter;

  @BeforeEach
  void setUp() {
    chatModel = mock(ChatModel.class);
    executor = Executors.newFixedThreadPool(4);
    ChatInferenceProperties properties = new ChatInferenceProperties();
    var objectMapper = JsonMapper.builder().findAndAddModules().build();
    var prompts =
        new AdminChatPromptFactory(new OperationContextRegistry(), properties, objectMapper, CLOCK);
    var schemas = new InternalDataSchemaRegistry(new JTokkitTokenCountEstimator(), properties);
    adapter =
        new SpringAiAdminChatInferenceAdapter(
            chatModel,
            prompts,
            schemas,
            properties,
            objectMapper,
            executor,
            new AdminDataTools(mock(com.openat.chat.application.port.AdminDataQueryPort.class)),
            new CryptoPriceTools(mock(com.openat.chat.application.port.CryptoPricePort.class)),
            new InternalDataSchemaSelector(),
            new InternalQuerySubmissionTools(),
            new OperationContextTools(new OperationContextRegistry()),
            new WeatherTools(mock(com.openat.chat.application.port.WeatherPort.class)),
            new WebSearchTools(
                mock(com.openat.chat.application.port.WebSearchPort.class),
                new ExternalSearchPolicy()));
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("1차 content와 tool call을 그대로 수집하고 도구는 자동 실행하지 않는다")
  void route_hybridResponse_preservesRawCompletion() {
    AssistantMessage.ToolCall toolCall =
        toolCall("loadInternalDataSchemas", "{\"domains\":[\"ORDER_SALES\"]}");
    given(chatModel.call(any(Prompt.class)))
        .willReturn(response("노출하면 안 되는 미검증 본문", List.of(toolCall)));

    RoutingResponse result = adapter.route(command("지난달 주문 수는?"), deadline());

    assertThat(result.content()).isEqualTo("노출하면 안 되는 미검증 본문");
    assertThat(result.toolInvocations())
        .singleElement()
        .satisfies(
            invocation -> {
              assertThat(invocation.name()).isEqualTo("loadInternalDataSchemas");
              assertThat(invocation.arguments()).contains("ORDER_SALES");
            });
    verify(chatModel).call(any(Prompt.class));
  }

  @Test
  @DisplayName("2차 bindings는 항목별로 검증해 정상 형제를 보존한다")
  void bind_partialInvalidItem_preservesSuccessfulSibling() {
    String arguments =
        """
        {
          "earlyAnswer": "",
          "bindings": [
            {
              "domain": "ORDER_SALES",
              "status": "SUCCESS",
              "query": {
                "dataset": "ORDER",
                "metrics": ["ORDER_COUNT"],
                "dimensions": [],
                "timeField": "CREATED_AT",
                "timeRange": "TODAY",
                "customStart": "",
                "customEndExclusive": "",
                "grain": "NONE",
                "comparison": "NONE",
                "filters": [],
                "orderBy": "ORDER_COUNT",
                "sortDirection": "DESC",
                "limit": 10
              },
              "failureReason": ""
            },
            {
              "domain": "ORDER_SALES",
              "status": "SUCCESS",
              "query": {
                "dataset": "MEMBER_CURRENT",
                "metrics": ["MEMBER_COUNT"],
                "dimensions": [],
                "timeField": "NONE",
                "timeRange": "CURRENT_SNAPSHOT",
                "grain": "NONE",
                "comparison": "NONE",
                "filters": [],
                "orderBy": "MEMBER_COUNT",
                "sortDirection": "DESC",
                "limit": 10
              },
              "failureReason": ""
            }
          ]
        }
        """;
    given(chatModel.call(any(Prompt.class)))
        .willReturn(response("", List.of(toolCall("submitInternalQueryBindings", arguments))));

    var result =
        adapter.bind(
            command("오늘 주문 수는?"), Set.of(InternalDataDomain.ORDER_SALES), List.of(), deadline());

    assertThat(result.bindings())
        .extracting(binding -> binding.status())
        .containsExactly(BindingStatus.SUCCESS, BindingStatus.FAILED);
    assertThat(result.bindings())
        .extracting(binding -> binding.id())
        .containsExactly("r2-s01-b01", "r2-s01-b02");
    verify(chatModel).call(any(Prompt.class));
  }

  @Test
  @DisplayName("2차 봉투가 깨졌을 때만 같은 shard를 한 번 복구한다")
  void bind_malformedEnvelope_repairsOnce() {
    String validArguments =
        """
        {
          "earlyAnswer": "",
          "bindings": [{
            "domain": "ORDER_SALES",
            "status": "FAILED",
            "query": null,
            "failureReason": "기간을 알 수 없음"
          }]
        }
        """;
    given(chatModel.call(any(Prompt.class)))
        .willReturn(
            response("", List.of(toolCall("submitInternalQueryBindings", "{broken"))),
            response("", List.of(toolCall("submitInternalQueryBindings", validArguments))));

    var result =
        adapter.bind(
            command("그때 주문은?"), Set.of(InternalDataDomain.ORDER_SALES), List.of(), deadline());

    assertThat(result.bindings())
        .singleElement()
        .satisfies(
            binding -> {
              assertThat(binding.status()).isEqualTo(BindingStatus.FAILED);
              assertThat(binding.failureReason()).isEqualTo("기간을 알 수 없음");
            });
    verify(chatModel, times(2)).call(any(Prompt.class));
  }

  @Test
  @DisplayName("최종 답변은 stop 종료 증거가 있을 때만 정상 완료한다")
  void streamAnswer_stopFinishReason_completes() {
    given(chatModel.stream(any(Prompt.class)))
        .willReturn(Flux.just(streamResponse("정상 답변", null), streamResponse("", "stop")));
    List<String> chunks = new java.util.ArrayList<>();

    adapter.streamAnswer(command("질문"), List.of(), chunks::add, deadline());

    assertThat(chunks).containsExactly("정상 답변");
  }

  @Test
  @DisplayName("일부 토큰 뒤 정상 종료 증거 없이 끝난 스트림은 실패한다")
  void streamAnswer_eofWithoutFinishReason_rejectsPartialAnswer() {
    given(chatModel.stream(any(Prompt.class))).willReturn(Flux.just(streamResponse("잘린 답변", null)));
    List<String> chunks = new java.util.ArrayList<>();

    assertThatThrownBy(
            () -> adapter.streamAnswer(command("질문"), List.of(), chunks::add, deadline()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("정상 종료 증거");
    assertThat(chunks).containsExactly("잘린 답변");
  }

  private AssistantMessage.ToolCall toolCall(String name, String arguments) {
    return new AssistantMessage.ToolCall("call-1", "function", name, arguments);
  }

  private ChatResponse response(String content, List<AssistantMessage.ToolCall> toolCalls) {
    AssistantMessage message =
        AssistantMessage.builder().content(content).toolCalls(toolCalls).build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  private ChatResponse streamResponse(String content, String finishReason) {
    AssistantMessage message = AssistantMessage.builder().content(content).build();
    ChatGenerationMetadata metadata =
        ChatGenerationMetadata.builder().finishReason(finishReason).build();
    return new ChatResponse(List.of(new Generation(message, metadata)));
  }

  private ChatCommand command(String message) {
    return new ChatCommand(UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), message);
  }

  private ChatRequestDeadline deadline() {
    return new ChatRequestDeadline(CLOCK.instant().plus(Duration.ofMinutes(2)), CLOCK);
  }
}

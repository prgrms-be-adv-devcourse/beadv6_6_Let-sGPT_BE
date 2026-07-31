package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.exception.AdminChatExecutionException.Reason;
import com.openat.chat.application.port.AdminChatInferencePort.BindingStatus;
import com.openat.chat.application.port.AdminChatInferencePort.RoutingResponse;
import com.openat.chat.application.port.ChatStreamClosedException;
import com.openat.chat.application.service.ExternalSearchPolicy;
import com.openat.chat.application.service.OperationContextRegistry;
import com.openat.chat.domain.query.InternalDataDomain;
import com.openat.chat.infrastructure.inference.InternalDataSchemaRegistry.SchemaShard;
import com.openat.chat.infrastructure.inference.tool.AdminDataTools;
import com.openat.chat.infrastructure.inference.tool.CryptoPriceTools;
import com.openat.chat.infrastructure.inference.tool.InternalDataSchemaSelector;
import com.openat.chat.infrastructure.inference.tool.InternalQuerySubmissionTools;
import com.openat.chat.infrastructure.inference.tool.OperationContextTools;
import com.openat.chat.infrastructure.inference.tool.WeatherTools;
import com.openat.chat.infrastructure.inference.tool.WebSearchTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Spring AI 관리자 챗봇 수동 추론 어댑터")
class SpringAiAdminChatInferenceAdapterTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);

  private ChatModel chatModel;
  private ExecutorService executor;
  private ChatInferenceProperties properties;
  private ObjectMapper objectMapper;
  private TokenCountEstimator tokenEstimator;
  private SimpleMeterRegistry meterRegistry;
  private ChatInferenceMetrics metrics;
  private SpringAiAdminChatInferenceAdapter adapter;

  @BeforeEach
  void setUp() {
    chatModel = mock(ChatModel.class);
    executor = Executors.newFixedThreadPool(4);
    properties = new ChatInferenceProperties();
    objectMapper = JsonMapper.builder().findAndAddModules().build();
    tokenEstimator = new JTokkitTokenCountEstimator();
    var schemas = new InternalDataSchemaRegistry(tokenEstimator, properties);
    meterRegistry = new SimpleMeterRegistry();
    metrics = new ChatInferenceMetrics(meterRegistry);
    var promptBudget = new ChatPromptBudgetGuard(tokenEstimator, properties, metrics);
    adapter = createAdapter(schemas, promptBudget);
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
          "deliveredEvidenceIds": [],
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
  @DisplayName("1차 응답의 종료 이유를 본문과 함께 보존한다")
  void route_contentOnlyResponse_preservesFinishReason() {
    given(chatModel.call(any(Prompt.class)))
        .willReturn(response("토큰 제한으로 잘린 답변", List.of(), "length"));

    RoutingResponse result = adapter.route(command("엑셀이 뭐야?"), deadline());

    assertThat(result.content()).isEqualTo("토큰 제한으로 잘린 답변");
    assertThat(result.finishReason()).isEqualTo("length");
    assertThat(result.hasCompletedAnswer()).isFalse();
  }

  @Test
  @DisplayName("1차 실제 호출 직전 입력 예산을 넘으면 모델을 호출하지 않는다")
  void route_inputBudgetExceeded_rejectsBeforeModelCall() {
    properties.getContext().setInputTokenLimit(1);

    assertThatThrownBy(() -> adapter.route(command("지난달 주문 수는?"), deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception ->
                assertThat(exception.reason()).isEqualTo(Reason.INPUT_BUDGET_EXCEEDED));
    verify(chatModel, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("직전 대화 때문에 1차 예산을 넘으면 현재 질문만 남겨 호출한다")
  void route_previousTurnExceedsBudget_retriesWithoutPreviousTurn() {
    TokenCountEstimator historySensitiveEstimator = mock(TokenCountEstimator.class);
    given(historySensitiveEstimator.estimate(anyString()))
        .willAnswer(
            invocation ->
                invocation.getArgument(0, String.class).contains("<previous-question>")
                    ? 6_001
                    : 100);
    var schemas = new InternalDataSchemaRegistry(historySensitiveEstimator, properties);
    adapter =
        createAdapter(
            schemas,
            new ChatPromptBudgetGuard(historySensitiveEstimator, properties, metrics));
    given(chatModel.call(any(Prompt.class))).willReturn(response("현재 질문 답변", List.of()));
    ChatCommand command =
        new ChatCommand(
            UUID.randomUUID(),
            "admin",
            Set.of("ROLE_ADMIN"),
            "그럼 지난달은?",
            new ChatCommand.PreviousTurn("이번 달 주문 수는?", "이번 달 주문은 10건이야."));

    RoutingResponse result = adapter.route(command, deadline());

    assertThat(result.content()).isEqualTo("현재 질문 답변");
    verify(chatModel).call(any(Prompt.class));
  }

  @Test
  @DisplayName("1차 호출 전 절대 기한이 지나면 timeout으로 구분한다")
  void route_expiredDeadline_reportsTimeout() {
    ChatRequestDeadline expired = new ChatRequestDeadline(CLOCK.instant(), CLOCK);

    assertThatThrownBy(() -> adapter.route(command("지난달 주문 수는?"), expired))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception -> assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT));
    verify(chatModel, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("1차 추론 실행기가 요청을 거부하면 busy로 구분한다")
  void route_rejectedExecutor_reportsBusy() {
    executor.shutdownNow();

    assertThatThrownBy(() -> adapter.route(command("지난달 주문 수는?"), deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception -> assertThat(exception.reason()).isEqualTo(Reason.BUSY));
    verify(chatModel, never()).call(any(Prompt.class));
  }

  @Test
  @DisplayName("1차 대기 스레드가 중단되면 자식 호출을 취소하고 cancellation으로 구분한다")
  void route_interruptedWait_cancelsChildAndReportsCancellation() throws Exception {
    CountDownLatch modelStarted = new CountDownLatch(1);
    CountDownLatch modelInterrupted = new CountDownLatch(1);
    given(chatModel.call(any(Prompt.class)))
        .willAnswer(
            ignored -> {
              modelStarted.countDown();
              try {
                new CountDownLatch(1).await();
                throw new AssertionError("중단되지 않은 추론 호출");
              } catch (InterruptedException exception) {
                modelInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
              }
            });
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread routeThread =
        new Thread(
            () -> {
              try {
                adapter.route(command("지난달 주문 수는?"), deadline());
              } catch (Throwable exception) {
                failure.set(exception);
              }
            });

    routeThread.start();
    assertThat(modelStarted.await(1, TimeUnit.SECONDS)).isTrue();
    routeThread.interrupt();
    routeThread.join(1_000);

    assertThat(routeThread.isAlive()).isFalse();
    assertThat(failure.get())
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception -> assertThat(exception.reason()).isEqualTo(Reason.CANCELLED));
    assertThat(modelInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("early answer는 실제 성공 근거에 속하는 서버 발급 id만 전달 완료로 인정한다")
  void bind_earlyAnswer_acceptsOnlyDeliverableEvidenceIds() {
    EvidenceSegment success =
        new EvidenceSegment(
            "r1-t01",
            EvidenceSegment.Status.SUCCESS,
            "WEATHER",
            java.util.Map.of("summary", "맑음"),
            List.of(),
            "Open-Meteo",
            "2026-07-24",
            false);
    EvidenceSegment failure =
        new EvidenceSegment(
            "r1-t02",
            EvidenceSegment.Status.FAILED,
            "INTERNAL_SCHEMA_SELECTION",
            null,
            List.of("영역 선택 실패"),
            "",
            "",
            false);
    String arguments =
        """
        {
          "earlyAnswer": "오늘 날씨는 맑아.",
          "deliveredEvidenceIds": ["r1-t01", "r1-t02", "unknown-id"],
          "bindings": [{
            "domain": "ORDER_SALES",
            "status": "FAILED",
            "query": null,
            "failureReason": "기간을 알 수 없음"
          }]
        }
        """;
    given(chatModel.call(any(Prompt.class)))
        .willReturn(response("", List.of(toolCall("submitInternalQueryBindings", arguments))));

    var result =
        adapter.bind(
            command("오늘 날씨와 주문 수는?"),
            Set.of(InternalDataDomain.ORDER_SALES),
            List.of(success, failure),
            deadline());

    assertThat(result.earlyAnswer()).isEqualTo("오늘 날씨는 맑아.");
    assertThat(result.deliveredEvidenceIds()).containsExactly(success.id());
  }

  @Test
  @DisplayName("2차 봉투가 깨졌을 때만 같은 shard를 한 번 복구한다")
  void bind_malformedEnvelope_repairsOnce() {
    String validArguments =
        """
        {
          "earlyAnswer": "",
          "deliveredEvidenceIds": [],
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
  @DisplayName("2차 형식 복구 호출도 실제 요청 직전 입력 예산을 다시 검증한다")
  void bind_repairInputBudgetExceeded_rejectsBeforeRepairCall() {
    TokenCountEstimator repairSensitiveEstimator = mock(TokenCountEstimator.class);
    given(repairSensitiveEstimator.estimate(anyString()))
        .willAnswer(
            invocation ->
                ((String) invocation.getArgument(0)).contains("직전 응답 형식")
                    ? properties.getContext().getInputTokenLimit() + 1
                    : 1);
    adapter =
        createAdapter(
            new InternalDataSchemaRegistry(tokenEstimator, properties),
            new ChatPromptBudgetGuard(repairSensitiveEstimator, properties, metrics));
    given(chatModel.call(any(Prompt.class)))
        .willReturn(
            response(
                "",
                List.of(
                    toolCall("submitInternalQueryBindings", "{broken"))));

    assertThatThrownBy(
            () ->
                adapter.bind(
                    command("그때 주문은?"),
                    Set.of(InternalDataDomain.ORDER_SALES),
                    List.of(),
                    deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception ->
                assertThat(exception.reason()).isEqualTo(Reason.INPUT_BUDGET_EXCEEDED));
    verify(chatModel).call(any(Prompt.class));
  }

  @Test
  @DisplayName("primary shard가 stage timeout이어도 완료된 secondary binding을 보존한다")
  void bind_timedOutPrimaryShard_keepsSuccessfulSibling() throws Exception {
    properties.setStageTimeout(Duration.ofMillis(500));
    InternalDataSchemaRegistry shardRegistry = mock(InternalDataSchemaRegistry.class);
    List<SchemaShard> shards =
        List.of(
            new SchemaShard(
                0, Set.of(InternalDataDomain.ORDER_SALES), "SLOW_SCHEMA", true),
            new SchemaShard(
                1, Set.of(InternalDataDomain.MEMBERSHIP), "FAST_SCHEMA", false));
    given(shardRegistry.shards(any(), any())).willReturn(shards);
    adapter =
        createAdapter(
            shardRegistry,
            new ChatPromptBudgetGuard(tokenEstimator, properties, metrics));
    CountDownLatch primaryStarted = new CountDownLatch(1);
    CountDownLatch primaryInterrupted = new CountDownLatch(1);
    String successfulArguments =
        """
        {
          "earlyAnswer": "",
          "deliveredEvidenceIds": [],
          "bindings": [{
            "domain": "MEMBERSHIP",
            "status": "SUCCESS",
            "query": {
              "dataset": "MEMBER_CURRENT",
              "metrics": ["MEMBER_COUNT"],
              "dimensions": [],
              "timeField": "NONE",
              "timeRange": "CURRENT_SNAPSHOT",
              "customStart": "",
              "customEndExclusive": "",
              "grain": "NONE",
              "comparison": "NONE",
              "filters": [],
              "orderBy": "MEMBER_COUNT",
              "sortDirection": "DESC",
              "limit": 10
            },
            "failureReason": ""
          }]
        }
        """;
    given(chatModel.call(any(Prompt.class)))
        .willAnswer(
            invocation -> {
              Prompt prompt = invocation.getArgument(0);
              if (prompt.getSystemMessage().getText().contains("SLOW_SCHEMA")) {
                primaryStarted.countDown();
                try {
                  new CountDownLatch(1).await();
                  throw new AssertionError("중단되지 않은 primary shard");
                } catch (InterruptedException exception) {
                  primaryInterrupted.countDown();
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(exception);
                }
              }
              return response(
                  "",
                  List.of(
                      toolCall("submitInternalQueryBindings", successfulArguments)));
            });

    var result =
        adapter.bind(
            command("오늘 주문과 회원 수를 알려줘"),
            Set.of(InternalDataDomain.ORDER_SALES, InternalDataDomain.MEMBERSHIP),
            List.of(),
            deadline());

    assertThat(primaryStarted.getCount()).isZero();
    assertThat(result.bindings())
        .extracting(binding -> binding.status())
        .containsExactly(BindingStatus.FAILED, BindingStatus.SUCCESS);
    assertThat(result.bindings())
        .extracting(binding -> binding.id())
        .containsExactly("r2-s01-b01", "r2-s02-b01");
    assertThat(primaryInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
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
  @DisplayName("최종 답변은 청크가 계속 와도 단계 시작 기준 제한을 넘으면 timeout으로 취소한다")
  void streamAnswer_continuousChunks_exceedAbsoluteStageTimeout() {
    properties.setStageTimeout(Duration.ofMillis(150));
    AtomicBoolean sourceCancelled = new AtomicBoolean();
    given(chatModel.stream(any(Prompt.class)))
        .willReturn(
            Flux.interval(Duration.ZERO, Duration.ofMillis(10))
                .take(60)
                .map(
                    sequence ->
                        sequence == 59
                            ? streamResponse("", "stop")
                            : streamResponse("조각", null))
                .doOnCancel(() -> sourceCancelled.set(true)));
    List<String> chunks = new java.util.ArrayList<>();

    assertThatThrownBy(
            () -> adapter.streamAnswer(command("질문"), List.of(), chunks::add, deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception -> assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT));
    assertThat(chunks).hasSizeGreaterThan(1).hasSizeLessThan(59);
    assertThat(sourceCancelled.get()).isTrue();
  }

  @Test
  @DisplayName("Reactor가 감싼 스트림 종료 예외는 원본을 보존하고 취소로 측정한다")
  void streamAnswer_wrappedStreamClosed_preservesOriginalFailureAndRecordsCancellation() {
    ChatStreamClosedException expected = new ChatStreamClosedException(null);
    given(chatModel.stream(any(Prompt.class)))
        .willReturn(
            Flux.<ChatResponse>error(
                new Exception("reactor wrapper", expected)));

    assertThatThrownBy(
            () ->
                adapter.streamAnswer(
                    command("질문"), List.of(), ignored -> {}, deadline()))
        .isSameAs(expected);
    assertThat(
            meterRegistry
                .get("ai.chat.inference.stage")
                .tags("stage", "answer", "outcome", "cancelled")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Reactor가 감싼 실행 예외는 사유와 원본을 보존한다")
  void streamAnswer_wrappedExecutionFailure_preservesOriginalFailure() {
    AdminChatExecutionException expected =
        new AdminChatExecutionException(Reason.BUSY, "실행기 포화");
    given(chatModel.stream(any(Prompt.class)))
        .willReturn(
            Flux.<ChatResponse>error(
                new Exception("reactor wrapper", expected)));

    assertThatThrownBy(
            () ->
                adapter.streamAnswer(
                    command("질문"), List.of(), ignored -> {}, deadline()))
        .isSameAs(expected);
  }

  @Test
  @DisplayName("Reactor가 감싼 cancellation은 typed cancellation으로 분류한다")
  void streamAnswer_wrappedCancellation_reportsCancellation() {
    given(chatModel.stream(any(Prompt.class)))
        .willReturn(
            Flux.<ChatResponse>error(
                new Exception(
                    "reactor wrapper",
                    new CancellationException("cancelled"))));

    assertThatThrownBy(
            () ->
                adapter.streamAnswer(
                    command("질문"), List.of(), ignored -> {}, deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception -> assertThat(exception.reason()).isEqualTo(Reason.CANCELLED));
  }

  @Test
  @DisplayName("최종 답변 실제 호출 직전 입력 예산을 넘으면 스트림을 열지 않는다")
  void streamAnswer_inputBudgetExceeded_rejectsBeforeModelCall() {
    properties.getContext().setInputTokenLimit(1);

    assertThatThrownBy(
            () ->
                adapter.streamAnswer(
                    command("질문"),
                    List.of(),
                    ignored -> {},
                    deadline()))
        .isInstanceOfSatisfying(
            AdminChatExecutionException.class,
            exception ->
                assertThat(exception.reason()).isEqualTo(Reason.INPUT_BUDGET_EXCEEDED));
    verify(chatModel, never()).stream(any(Prompt.class));
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

  private SpringAiAdminChatInferenceAdapter createAdapter(
      InternalDataSchemaRegistry schemas, ChatPromptBudgetGuard promptBudget) {
    var prompts =
        new AdminChatPromptFactory(
            new OperationContextRegistry(), properties, objectMapper, CLOCK);
    return new SpringAiAdminChatInferenceAdapter(
        chatModel,
        prompts,
        schemas,
        properties,
        objectMapper,
        executor,
        promptBudget,
        metrics,
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

  private AssistantMessage.ToolCall toolCall(String name, String arguments) {
    return new AssistantMessage.ToolCall("call-1", "function", name, arguments);
  }

  private ChatResponse response(String content, List<AssistantMessage.ToolCall> toolCalls) {
    return response(content, toolCalls, null);
  }

  private ChatResponse response(
      String content, List<AssistantMessage.ToolCall> toolCalls, String finishReason) {
    AssistantMessage message =
        AssistantMessage.builder().content(content).toolCalls(toolCalls).build();
    ChatGenerationMetadata metadata =
        ChatGenerationMetadata.builder().finishReason(finishReason).build();
    return new ChatResponse(List.of(new Generation(message, metadata)));
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

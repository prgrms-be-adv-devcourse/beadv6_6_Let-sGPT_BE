package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.exception.AdminChatExecutionException.Reason;
import com.openat.chat.application.port.AdminChatInferencePort;
import com.openat.chat.application.port.ChatStreamClosedException;
import com.openat.chat.domain.planning.TimeRangePreset;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.InternalDataDomain;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.Outcome;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.Stage;
import com.openat.chat.infrastructure.inference.InternalDataSchemaRegistry.SchemaShard;
import com.openat.chat.infrastructure.inference.tool.AdminDataTools;
import com.openat.chat.infrastructure.inference.tool.CryptoPriceTools;
import com.openat.chat.infrastructure.inference.tool.InternalDataSchemaSelector;
import com.openat.chat.infrastructure.inference.tool.InternalQuerySubmissionTools;
import com.openat.chat.infrastructure.inference.tool.OperationContextTools;
import com.openat.chat.infrastructure.inference.tool.WeatherTools;
import com.openat.chat.infrastructure.inference.tool.WebSearchTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class SpringAiAdminChatInferenceAdapter implements AdminChatInferencePort {

  private static final Logger log =
      LoggerFactory.getLogger(SpringAiAdminChatInferenceAdapter.class);
  private static final String BINDING_TOOL = "submitInternalQueryBindings";
  private static final int MAX_EARLY_ANSWER_CHARACTERS = 2_500;
  private static final String BINDING_REPAIR_INSTRUCTION =
      "\n직전 응답 형식이 올바르지 않았다. 이번에는 정확히 하나의 "
          + BINDING_TOOL
          + " 호출만 반환한다.";

  private final ChatModel chatModel;
  private final AdminChatPromptFactory prompts;
  private final InternalDataSchemaRegistry schemas;
  private final ChatInferenceProperties properties;
  private final ObjectMapper objectMapper;
  private final ExecutorService taskExecutor;
  private final ChatPromptBudgetGuard promptBudget;
  private final ChatInferenceMetrics metrics;
  private final List<ToolCallback> routingTools;
  private final ToolCallback bindingTool;

  public SpringAiAdminChatInferenceAdapter(
      ChatModel chatModel,
      AdminChatPromptFactory prompts,
      InternalDataSchemaRegistry schemas,
      ChatInferenceProperties properties,
      ObjectMapper objectMapper,
      @Qualifier("chatTaskExecutor") ExecutorService taskExecutor,
      ChatPromptBudgetGuard promptBudget,
      ChatInferenceMetrics metrics,
      AdminDataTools adminDataTools,
      CryptoPriceTools cryptoPriceTools,
      InternalDataSchemaSelector schemaSelector,
      InternalQuerySubmissionTools querySubmissionTools,
      OperationContextTools operationContextTools,
      WeatherTools weatherTools,
      WebSearchTools webSearchTools) {
    this.chatModel = chatModel;
    this.prompts = prompts;
    this.schemas = schemas;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.taskExecutor = taskExecutor;
    this.promptBudget = promptBudget;
    this.metrics = metrics;
    this.routingTools =
        Arrays.asList(
            ToolCallbacks.from(
                adminDataTools,
                cryptoPriceTools,
                schemaSelector,
                operationContextTools,
                weatherTools,
                webSearchTools));
    this.bindingTool = ToolCallbacks.from(querySubmissionTools)[0];
  }

  @Override
  public boolean isAvailable() {
    return properties.isEnabled() && properties.isLocalOnlyRoute();
  }

  @Override
  public RoutingResponse route(ChatCommand command, ChatRequestDeadline deadline) {
    long startedAt = System.nanoTime();
    Outcome outcome = Outcome.ERROR;
    try {
      RoutingResponse first = routeOnce(command, deadline);
      if (!first.content().isBlank() || first.hasTools()) {
        logStage(command, "ROUTING", startedAt, "tools=" + first.toolInvocations().size());
        outcome = Outcome.SUCCESS;
        return first;
      }
      RoutingResponse retry = routeOnce(command, deadline);
      logStage(
          command,
          "ROUTING",
          startedAt,
          "tools=" + retry.toolInvocations().size() + ",emptyRetry=true");
      outcome = Outcome.SUCCESS;
      return retry;
    } catch (AdminChatExecutionException exception) {
      outcome = metrics.outcome(exception);
      throw exception;
    } finally {
      metrics.recordStage(Stage.ROUTING, outcome, startedAt);
    }
  }

  @Override
  public BindingResponse bind(
      ChatCommand command,
      Set<InternalDataDomain> domains,
      List<EvidenceSegment> evidence,
      ChatRequestDeadline deadline) {
    long startedAt = System.nanoTime();
    Outcome outcome = Outcome.ERROR;
    try {
      String fixedPrompt =
          prompts.bindingSystem("", true)
              + BINDING_REPAIR_INSTRUCTION
              + prompts.bindingUser(command, evidence, true)
              + toolDefinitionText(bindingTool);
      List<SchemaShard> shards = schemas.shards(domains, fixedPrompt);
      Set<String> deliverableEvidenceIds = deliverableEvidenceIds(evidence);
      List<ShardBindingResponse> responses =
          executeShards(command, evidence, deliverableEvidenceIds, shards, deadline);
      responses.sort(Comparator.comparingInt(ShardBindingResponse::shardIndex));

      ShardBindingResponse primary =
          responses.stream()
              .filter(response -> response.shardIndex() == 0)
              .findFirst()
              .orElse(new ShardBindingResponse(0, "", Set.of(), List.of()));
      List<QueryBinding> merged = new ArrayList<>();
      Set<QuerySpec> uniqueQueries = new LinkedHashSet<>();
      for (ShardBindingResponse response : responses) {
        for (QueryBinding binding : response.bindings()) {
          if (binding.status() == BindingStatus.SUCCESS
              && binding.query() != null
              && !uniqueQueries.add(binding.query())) {
            continue;
          }
          merged.add(binding);
        }
      }
      BindingResponse result =
          new BindingResponse(
              primary.earlyAnswer(),
              primary.deliveredEvidenceIds(),
              List.copyOf(merged));
      logStage(
          command,
          "BINDING",
          startedAt,
          "shards="
              + shards.size()
              + ",bindings="
              + result.bindings().size()
              + ",summary="
              + bindingSummary(result.bindings()));
      outcome = Outcome.SUCCESS;
      return result;
    } catch (AdminChatExecutionException exception) {
      outcome = metrics.outcome(exception);
      throw exception;
    } finally {
      metrics.recordStage(Stage.BINDING, outcome, startedAt);
    }
  }

  @Override
  public void streamAnswer(
      ChatCommand command,
      List<EvidenceSegment> evidence,
      Consumer<String> chunkConsumer,
      ChatRequestDeadline deadline) {
    long startedAt = System.nanoTime();
    Outcome outcome = Outcome.ERROR;
    try {
      AtomicBoolean firstChunk = new AtomicBoolean(true);
      AtomicReference<String> finishReason = new AtomicReference<>("");
      String system = prompts.answerSystem();
      String user =
          budgetedUser(
              Stage.ANSWER,
              system,
              command,
              candidate -> prompts.answerUser(candidate, evidence),
              List.of());
      OpenAiChatOptions options = baseOptions(properties.getAnswerMaxTokens()).build();
      Prompt prompt = prompt(system, user, options);

      Flux<String> content =
          chatModel.stream(prompt)
              .doOnNext(response -> captureFinishReason(response, finishReason))
              .flatMapIterable(
                  response -> {
                    if (response == null || response.getResult() == null) {
                      return List.<String>of();
                    }
                    String text = response.getResult().getOutput().getText();
                    return text == null || text.isEmpty() ? List.<String>of() : List.of(text);
                  });
      Duration timeout = boundedStreamingTimeout(deadline, startedAt);
      AtomicBoolean timedOut = new AtomicBoolean();
      try {
        content
            .doOnNext(
                chunk -> {
                  if (firstChunk.compareAndSet(true, false)) {
                    logStage(command, "ANSWER_FIRST_CHUNK", startedAt, "");
                  }
                  chunkConsumer.accept(chunk);
                })
            .takeUntilOther(
                Mono.delay(timeout)
                    .doOnNext(ignored -> timedOut.set(true)))
            .blockLast();
      } catch (RuntimeException exception) {
        throw classifyStreamingFailure(exception);
      }
      if (timedOut.get()) {
        throw new AdminChatExecutionException(
            Reason.TIMEOUT, "답변 스트림 시간이 초과됐어요.");
      }
      if (!"stop".equalsIgnoreCase(finishReason.get())) {
        throw new IllegalStateException("추론 서버가 정상 종료 증거 없이 답변 스트림을 끝냈어요.");
      }
      logStage(command, "ANSWER", startedAt, "");
      outcome = Outcome.SUCCESS;
    } catch (ChatStreamClosedException exception) {
      outcome = Outcome.CANCELLED;
      throw exception;
    } catch (AdminChatExecutionException exception) {
      outcome = metrics.outcome(exception);
      throw exception;
    } finally {
      metrics.recordStage(Stage.ANSWER, outcome, startedAt);
    }
  }

  private void captureFinishReason(ChatResponse response, AtomicReference<String> finishReason) {
    String value = finishReason(response);
    if (value != null && !value.isBlank()) {
      finishReason.set(value);
    }
  }

  private RoutingResponse routeOnce(ChatCommand command, ChatRequestDeadline deadline) {
    String system = prompts.routingSystem();
    String user =
        budgetedUser(
            Stage.ROUTING, system, command, prompts::routingUser, routingTools);
    OpenAiChatOptions options =
        baseOptions(properties.getRoutingMaxTokens())
            .toolCallbacks(routingTools)
            .toolChoice("auto")
            .parallelToolCalls(true)
            .build();
    Prompt prompt = prompt(system, user, options);
    ChatResponse response = callWithDeadline(() -> chatModel.call(prompt), deadline);
    AssistantMessage output = output(response);
    return new RoutingResponse(
        output.getText(), toolInvocations(output), finishReason(response));
  }

  private ShardBindingResponse bindShard(
      ChatCommand command,
      List<EvidenceSegment> evidence,
      Set<String> deliverableEvidenceIds,
      SchemaShard shard,
      ChatRequestDeadline deadline,
      boolean repair) {
    String system = prompts.bindingSystem(shard.schema(), shard.primary());
    if (repair) {
      system += BINDING_REPAIR_INSTRUCTION;
    }
    String user =
        budgetedUser(
            Stage.BINDING,
            system,
            command,
            candidate -> prompts.bindingUser(candidate, evidence, shard.primary()),
            List.of(bindingTool));
    OpenAiChatOptions options =
        baseOptions(properties.getBindingMaxTokens())
            .toolCallbacks(List.of(bindingTool))
            .toolChoice("required")
            .parallelToolCalls(false)
            .build();

    try {
      boundedTimeout(deadline);
      AssistantMessage response = output(chatModel.call(prompt(system, user, options)));
      List<AssistantMessage.ToolCall> calls =
          response.getToolCalls().stream()
              .filter(call -> BINDING_TOOL.equals(call.name()))
              .toList();
      if (response.getToolCalls().size() != 1 || calls.size() != 1) {
        throw new IllegalArgumentException("구조화 도구 호출은 정확히 하나여야 해요.");
      }
      return parseBindingArguments(
          shard, calls.getFirst().arguments(), deliverableEvidenceIds);
    } catch (AdminChatExecutionException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      if (!repair) {
        return bindShard(
            command, evidence, deliverableEvidenceIds, shard, deadline, true);
      }
      return failedShard(shard, "구조화 응답 형식을 두 번 확인했지만 읽지 못했어요.");
    } catch (RuntimeException exception) {
      return failedShard(shard, "구조화 추론 요청을 완료하지 못했어요.");
    }
  }

  private ShardBindingResponse parseBindingArguments(
      SchemaShard shard, String arguments, Set<String> deliverableEvidenceIds) {
    JsonNode root;
    try {
      root = objectMapper.readTree(arguments);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("구조화 JSON을 읽지 못했어요.", exception);
    }
    JsonNode bindingsNode = root.get("bindings");
    if (bindingsNode == null || !bindingsNode.isArray() || bindingsNode.isEmpty()) {
      throw new IllegalArgumentException("구조화 bindings가 비어 있어요.");
    }

    String earlyAnswer = shard.primary() ? safeEarlyAnswer(text(root, "earlyAnswer")) : "";
    Set<String> deliveredEvidenceIds =
        shard.primary()
            ? validatedDeliveredEvidenceIds(
                root.get("deliveredEvidenceIds"), deliverableEvidenceIds)
            : Set.of();
    if (earlyAnswer.isBlank() || deliveredEvidenceIds.isEmpty()) {
      earlyAnswer = "";
      deliveredEvidenceIds = Set.of();
    }
    List<QueryBinding> bindings = new ArrayList<>();
    int itemIndex = 0;
    for (JsonNode bindingNode : bindingsNode) {
      String id = "r2-s%02d-b%02d".formatted(shard.index() + 1, itemIndex + 1);
      bindings.add(parseBinding(id, shard.domains(), bindingNode));
      itemIndex++;
    }
    return new ShardBindingResponse(
        shard.index(), earlyAnswer, deliveredEvidenceIds, List.copyOf(bindings));
  }

  private QueryBinding parseBinding(
      String id, Set<InternalDataDomain> allowedDomains, JsonNode node) {
    InternalDataDomain domain = enumValue(text(node, "domain"), InternalDataDomain.class);
    if (domain == null || !allowedDomains.contains(domain)) {
      return new QueryBinding(id, domain, BindingStatus.FAILED, null, "선택된 shard에 없는 내부 데이터 영역");
    }

    BindingStatus status = enumValue(text(node, "status"), BindingStatus.class);
    if (status == BindingStatus.FAILED) {
      return new QueryBinding(
          id, domain, status, null, defaultFailureReason(text(node, "failureReason")));
    }
    if (status != BindingStatus.SUCCESS) {
      return new QueryBinding(id, domain, BindingStatus.FAILED, null, "구조화 상태를 확인할 수 없음");
    }

    JsonNode query = node.get("query");
    if (query == null || !query.isObject()) {
      return new QueryBinding(id, domain, BindingStatus.FAILED, null, "SUCCESS 조회 조건이 비어 있음");
    }
    QuerySpec querySpec =
        new QuerySpec(
            enumValue(text(query, "dataset"), Dataset.class),
            texts(query.get("metrics")),
            texts(query.get("dimensions")),
            text(query, "timeField"),
            enumValue(text(query, "timeRange"), TimeRangePreset.class),
            text(query, "customStart"),
            text(query, "customEndExclusive"),
            enumValue(text(query, "grain"), TrendGrain.class),
            enumValue(text(query, "comparison"), Comparison.class),
            filters(query.get("filters")),
            text(query, "orderBy"),
            enumValue(text(query, "sortDirection"), SortDirection.class),
            integer(query, "limit"));
    if (!domain.supports(querySpec.dataset())) {
      return new QueryBinding(id, domain, BindingStatus.FAILED, null, "선택 영역에서 지원하지 않는 dataset");
    }
    return new QueryBinding(id, domain, status, querySpec, "");
  }

  private List<FilterSpec> filters(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<FilterSpec> filters = new ArrayList<>();
    for (JsonNode item : node) {
      filters.add(new FilterSpec(text(item, "field"), texts(item.get("values"))));
    }
    return List.copyOf(filters);
  }

  private String bindingSummary(List<QueryBinding> bindings) {
    return bindings.stream()
        .map(
            binding -> {
              QuerySpec query = binding.query();
              if (query == null) {
                return "%s:%s".formatted(binding.domain(), binding.status());
              }
              return "%s:%s:%s:%s:%s:%s:%s"
                  .formatted(
                      binding.domain(),
                      binding.status(),
                      query.dataset(),
                      query.metrics(),
                      query.dimensions(),
                      query.timeRange(),
                      query.timeField());
            })
        .toList()
        .toString();
  }

  private List<String> texts(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      if (item.isTextual()) {
        values.add(item.asText());
      }
    }
    return List.copyOf(values);
  }

  private String budgetedUser(
      Stage stage,
      String system,
      ChatCommand command,
      Function<ChatCommand, String> userFactory,
      List<ToolCallback> tools) {
    String preferred = userFactory.apply(command);
    try {
      promptBudget.verify(stage, system, preferred, tools);
      return preferred;
    } catch (AdminChatExecutionException exception) {
      if (exception.reason() != Reason.INPUT_BUDGET_EXCEEDED
          || command.previousTurnContext().isEmpty()) {
        throw exception;
      }
      String withoutPreviousTurn = userFactory.apply(command.withoutPreviousTurn());
      promptBudget.verify(stage, system, withoutPreviousTurn, tools);
      return withoutPreviousTurn;
    }
  }

  private Set<String> deliverableEvidenceIds(List<EvidenceSegment> evidence) {
    return evidence.stream()
        .filter(segment -> segment.status() != EvidenceSegment.Status.FAILED)
        .map(EvidenceSegment::id)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private Set<String> validatedDeliveredEvidenceIds(
      JsonNode node, Set<String> deliverableEvidenceIds) {
    Set<String> validated = new LinkedHashSet<>();
    for (String id : texts(node)) {
      if (deliverableEvidenceIds.contains(id)) {
        validated.add(id);
      }
    }
    return Set.copyOf(validated);
  }

  private ShardBindingResponse failedShard(SchemaShard shard, String reason) {
    List<QueryBinding> failures = new ArrayList<>();
    int index = 0;
    for (InternalDataDomain domain :
        shard.domains().stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList()) {
      failures.add(
          new QueryBinding(
              "r2-s%02d-b%02d".formatted(shard.index() + 1, index + 1),
              domain,
              BindingStatus.FAILED,
              null,
              reason));
      index++;
    }
    return new ShardBindingResponse(shard.index(), "", Set.of(), List.copyOf(failures));
  }

  private AssistantMessage output(ChatResponse response) {
    if (response == null || response.getResult() == null) {
      throw new IllegalStateException("추론 서버가 빈 응답을 반환했어요.");
    }
    return response.getResult().getOutput();
  }

  private String finishReason(ChatResponse response) {
    if (response == null
        || response.getResult() == null
        || response.getResult().getMetadata() == null) {
      return "";
    }
    String finishReason = response.getResult().getMetadata().getFinishReason();
    return finishReason == null ? "" : finishReason;
  }

  private List<ToolInvocation> toolInvocations(AssistantMessage message) {
    return message.getToolCalls().stream()
        .map(call -> new ToolInvocation(call.id(), call.name(), call.arguments()))
        .toList();
  }

  private OpenAiChatOptions.Builder baseOptions(int maxTokens) {
    return OpenAiChatOptions.builder()
        .model(properties.getModel())
        .temperature(0.0)
        .maxTokens(maxTokens)
        .maxRetries(0)
        .reasoningEffort(properties.getReasoningEffort())
        .store(false);
  }

  private Prompt prompt(String system, String user, OpenAiChatOptions options) {
    return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options);
  }

  private <T> T callWithDeadline(Supplier<T> operation, ChatRequestDeadline deadline) {
    Duration timeout = boundedTimeout(deadline);
    Future<T> future;
    try {
      future = taskExecutor.submit(operation::get);
    } catch (RejectedExecutionException exception) {
      throw new AdminChatExecutionException(
          Reason.BUSY, "추론 실행기가 포화됐어요.", exception);
    }
    try {
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new AdminChatExecutionException(
          Reason.CANCELLED, "추론 요청이 취소됐어요.", exception);
    } catch (ExecutionException exception) {
      throw propagate(exception.getCause());
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "추론 단계 응답 시간이 초과됐어요.", exception);
    }
  }

  private List<ShardBindingResponse> executeShards(
      ChatCommand command,
      List<EvidenceSegment> evidence,
      Set<String> deliverableEvidenceIds,
      List<SchemaShard> shards,
      ChatRequestDeadline deadline) {
    if (shards.isEmpty()) {
      return List.of();
    }
    StageBudget budget = stageBudget(deadline);
    List<Callable<ShardBindingResponse>> tasks =
        shards.stream()
            .<Callable<ShardBindingResponse>>map(
                shard ->
                    () ->
                        bindShard(
                            command,
                            evidence,
                            deliverableEvidenceIds,
                            shard,
                            deadline,
                            false))
            .toList();
    List<Future<ShardBindingResponse>> futures;
    try {
      futures =
          taskExecutor.invokeAll(tasks, budget.timeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AdminChatExecutionException(
          Reason.CANCELLED, "병렬 추론 요청이 취소됐어요.", exception);
    } catch (RejectedExecutionException exception) {
      throw new AdminChatExecutionException(
          Reason.BUSY, "병렬 추론 실행기가 포화됐어요.", exception);
    }

    boolean timedOut = futures.stream().anyMatch(Future::isCancelled);
    if (timedOut && budget.requestDeadlineBound()) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "병렬 추론 중 요청 기한이 지났어요.");
    }

    List<ShardBindingResponse> responses = new ArrayList<>();
    for (int index = 0; index < futures.size(); index++) {
      SchemaShard shard = shards.get(index);
      try {
        responses.add(futures.get(index).get());
      } catch (CancellationException exception) {
        responses.add(failedShard(shard, "구조화 추론 시간이 초과됐어요."));
      } catch (ExecutionException exception) {
        if (exception.getCause() instanceof AdminChatExecutionException executionException) {
          throw executionException;
        }
        responses.add(failedShard(shard, "구조화 추론 요청을 완료하지 못했어요."));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AdminChatExecutionException(
            Reason.CANCELLED, "병렬 추론 결과 수집이 취소됐어요.", exception);
      }
    }
    return responses;
  }

  private Duration boundedTimeout(ChatRequestDeadline deadline) {
    try {
      return deadline.boundedBy(properties.getStageTimeout());
    } catch (TimeoutException exception) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "관리자 챗봇 요청 기한이 지났어요.", exception);
    }
  }

  private Duration boundedStreamingTimeout(
      ChatRequestDeadline deadline, long stageStartedAt) {
    Duration deadlineTimeout = boundedTimeout(deadline);
    Duration elapsed =
        Duration.ofNanos(Math.max(0L, System.nanoTime() - stageStartedAt));
    Duration stageRemaining = properties.getStageTimeout().minus(elapsed);
    if (stageRemaining.isNegative() || stageRemaining.isZero()) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "답변 스트림 시간이 초과됐어요.");
    }
    return deadlineTimeout.compareTo(stageRemaining) <= 0
        ? deadlineTimeout
        : stageRemaining;
  }

  private RuntimeException propagate(Throwable cause) {
    if (cause instanceof AdminChatExecutionException executionException) {
      return executionException;
    }
    return cause instanceof RuntimeException runtime
        ? runtime
        : new IllegalStateException("추론 단계를 완료하지 못했어요.", cause);
  }

  private StageBudget stageBudget(ChatRequestDeadline deadline) {
    try {
      Duration remaining = deadline.remaining();
      Duration stageTimeout = properties.getStageTimeout();
      return new StageBudget(
          remaining.compareTo(stageTimeout) <= 0 ? remaining : stageTimeout,
          remaining.compareTo(stageTimeout) <= 0);
    } catch (TimeoutException exception) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "병렬 추론 전 요청 기한이 지났어요.", exception);
    }
  }

  private RuntimeException classifyStreamingFailure(RuntimeException exception) {
    Throwable cause = Exceptions.unwrap(exception);
    for (Throwable current = cause; current != null; current = current.getCause()) {
      if (current instanceof ChatStreamClosedException streamClosedException) {
        return streamClosedException;
      }
      if (current instanceof AdminChatExecutionException executionException) {
        return executionException;
      }
      if (current instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        return new AdminChatExecutionException(
            Reason.CANCELLED, "답변 스트림이 취소됐어요.", exception);
      }
      if (current instanceof CancellationException || Exceptions.isCancel(current)) {
        return new AdminChatExecutionException(
            Reason.CANCELLED, "답변 스트림이 취소됐어요.", exception);
      }
      if (current instanceof TimeoutException) {
        return new AdminChatExecutionException(
            Reason.TIMEOUT, "답변 스트림 시간이 초과됐어요.", exception);
      }
    }
    return exception;
  }

  private String toolDefinitionText(ToolCallback tool) {
    return tool.getToolDefinition().name()
        + tool.getToolDefinition().description()
        + tool.getToolDefinition().inputSchema();
  }

  private void logStage(ChatCommand command, String stage, long startedAt, String details) {
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
    log.info(
        "Admin chat inference stage completed. requestId={} stage={} elapsedMs={} {}",
        command.requestId(),
        stage,
        elapsedMillis,
        details);
  }

  private String safeEarlyAnswer(String value) {
    String answer = value == null ? "" : value.strip();
    if (answer.length() > MAX_EARLY_ANSWER_CHARACTERS) {
      return "";
    }
    return answer;
  }

  private String defaultFailureReason(String value) {
    return value == null || value.isBlank() ? "조회 조건을 채우지 못함" : value.strip();
  }

  private String text(JsonNode node, String field) {
    if (node == null) {
      return "";
    }
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : "";
  }

  private int integer(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.isIntegralNumber() ? value.asInt() : 0;
  }

  private <T extends Enum<T>> T enumValue(String value, Class<T> type) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private record ShardBindingResponse(
      int shardIndex,
      String earlyAnswer,
      Set<String> deliveredEvidenceIds,
      List<QueryBinding> bindings) {}

  private record StageBudget(Duration timeout, boolean requestDeadlineBound) {}
}

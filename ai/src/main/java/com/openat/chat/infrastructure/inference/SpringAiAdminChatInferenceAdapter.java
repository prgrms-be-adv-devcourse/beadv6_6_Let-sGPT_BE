package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.port.AdminChatInferencePort;
import com.openat.chat.domain.planning.TimeRangePreset;
import com.openat.chat.domain.planning.TrendGrain;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Comparison;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.Dataset;
import com.openat.chat.domain.query.AdminAnalyticsQueryPlan.SortDirection;
import com.openat.chat.domain.query.InternalDataDomain;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class SpringAiAdminChatInferenceAdapter implements AdminChatInferencePort {

  private static final Logger log =
      LoggerFactory.getLogger(SpringAiAdminChatInferenceAdapter.class);
  private static final String BINDING_TOOL = "submitInternalQueryBindings";
  private static final int MAX_EARLY_ANSWER_CHARACTERS = 2_500;

  private final ChatModel chatModel;
  private final AdminChatPromptFactory prompts;
  private final InternalDataSchemaRegistry schemas;
  private final ChatInferenceProperties properties;
  private final ObjectMapper objectMapper;
  private final ExecutorService taskExecutor;
  private final List<ToolCallback> routingTools;
  private final ToolCallback bindingTool;

  public SpringAiAdminChatInferenceAdapter(
      ChatModel chatModel,
      AdminChatPromptFactory prompts,
      InternalDataSchemaRegistry schemas,
      ChatInferenceProperties properties,
      ObjectMapper objectMapper,
      @Qualifier("chatTaskExecutor") ExecutorService taskExecutor,
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
    RoutingResponse first = routeOnce(command, deadline);
    if (!first.content().isBlank() || first.hasTools()) {
      logStage(command, "ROUTING", startedAt, "tools=" + first.toolInvocations().size());
      return first;
    }
    RoutingResponse retry = routeOnce(command, deadline);
    logStage(
        command,
        "ROUTING",
        startedAt,
        "tools=" + retry.toolInvocations().size() + ",emptyRetry=true");
    return retry;
  }

  @Override
  public BindingResponse bind(
      ChatCommand command,
      Set<InternalDataDomain> domains,
      List<EvidenceSegment> evidence,
      ChatRequestDeadline deadline) {
    long startedAt = System.nanoTime();
    String fixedPrompt =
        prompts.bindingSystem("", true)
            + prompts.bindingUser(command, evidence, true)
            + bindingTool.getToolDefinition().inputSchema();
    List<SchemaShard> shards = schemas.shards(domains, fixedPrompt);
    List<Future<ShardBindingResponse>> futures = new ArrayList<>();
    for (SchemaShard shard : shards) {
      futures.add(taskExecutor.submit(() -> bindShard(command, evidence, shard, deadline, false)));
    }

    List<ShardBindingResponse> responses = awaitAll(futures, deadline);
    responses.sort(Comparator.comparingInt(ShardBindingResponse::shardIndex));

    String earlyAnswer =
        responses.stream()
            .filter(response -> response.shardIndex() == 0)
            .map(ShardBindingResponse::earlyAnswer)
            .findFirst()
            .orElse("");
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
    BindingResponse result = new BindingResponse(earlyAnswer, List.copyOf(merged));
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
    return result;
  }

  @Override
  public void streamAnswer(
      ChatCommand command,
      List<EvidenceSegment> evidence,
      Consumer<String> chunkConsumer,
      ChatRequestDeadline deadline) {
    long startedAt = System.nanoTime();
    AtomicBoolean firstChunk = new AtomicBoolean(true);
    AtomicReference<String> finishReason = new AtomicReference<>("");
    OpenAiChatOptions options = baseOptions(properties.getAnswerMaxTokens()).build();
    Prompt prompt = prompt(prompts.answerSystem(), prompts.answerUser(command, evidence), options);
    Duration timeout = boundedTimeout(deadline);

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
    content
        .doOnNext(
            chunk -> {
              if (firstChunk.compareAndSet(true, false)) {
                logStage(command, "ANSWER_FIRST_CHUNK", startedAt, "");
              }
              chunkConsumer.accept(chunk);
            })
        .blockLast(timeout);
    if (!"stop".equalsIgnoreCase(finishReason.get())) {
      throw new IllegalStateException("추론 서버가 정상 종료 증거 없이 답변 스트림을 끝냈어요.");
    }
    logStage(command, "ANSWER", startedAt, "");
  }

  private void captureFinishReason(ChatResponse response, AtomicReference<String> finishReason) {
    if (response == null
        || response.getResult() == null
        || response.getResult().getMetadata() == null) {
      return;
    }
    String value = response.getResult().getMetadata().getFinishReason();
    if (value != null && !value.isBlank()) {
      finishReason.set(value);
    }
  }

  private RoutingResponse routeOnce(ChatCommand command, ChatRequestDeadline deadline) {
    OpenAiChatOptions options =
        baseOptions(properties.getRoutingMaxTokens())
            .toolCallbacks(routingTools)
            .toolChoice("auto")
            .parallelToolCalls(true)
            .build();
    Prompt prompt = prompt(prompts.routingSystem(), prompts.routingUser(command), options);
    AssistantMessage response = callWithDeadline(() -> output(chatModel.call(prompt)), deadline);
    return new RoutingResponse(response.getText(), toolInvocations(response));
  }

  private ShardBindingResponse bindShard(
      ChatCommand command,
      List<EvidenceSegment> evidence,
      SchemaShard shard,
      ChatRequestDeadline deadline,
      boolean repair) {
    String system = prompts.bindingSystem(shard.schema(), shard.primary());
    if (repair) {
      system += "\n직전 응답 형식이 올바르지 않았다. 이번에는 정확히 하나의 " + BINDING_TOOL + " 호출만 반환한다.";
    }
    String user = prompts.bindingUser(command, evidence, shard.primary());
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
      return parseBindingArguments(shard, calls.getFirst().arguments());
    } catch (IllegalArgumentException exception) {
      if (!repair) {
        return bindShard(command, evidence, shard, deadline, true);
      }
      return failedShard(shard, "구조화 응답 형식을 두 번 확인했지만 읽지 못했어요.");
    } catch (RuntimeException exception) {
      return failedShard(shard, "구조화 추론 요청을 완료하지 못했어요.");
    }
  }

  private ShardBindingResponse parseBindingArguments(SchemaShard shard, String arguments) {
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
    List<QueryBinding> bindings = new ArrayList<>();
    int itemIndex = 0;
    for (JsonNode bindingNode : bindingsNode) {
      String id = "r2-s%02d-b%02d".formatted(shard.index() + 1, itemIndex + 1);
      bindings.add(parseBinding(id, shard.domains(), bindingNode));
      itemIndex++;
    }
    return new ShardBindingResponse(shard.index(), earlyAnswer, List.copyOf(bindings));
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
    return new ShardBindingResponse(shard.index(), "", List.copyOf(failures));
  }

  private AssistantMessage output(ChatResponse response) {
    if (response == null || response.getResult() == null) {
      throw new IllegalStateException("추론 서버가 빈 응답을 반환했어요.");
    }
    return response.getResult().getOutput();
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
    Future<T> future = taskExecutor.submit(operation::get);
    try {
      Duration timeout = boundedTimeout(deadline);
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("추론 요청이 취소됐어요.", exception);
    } catch (ExecutionException exception) {
      throw propagate(exception.getCause());
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new IllegalStateException("추론 단계 응답 시간이 초과됐어요.", exception);
    }
  }

  private <T> List<T> awaitAll(List<Future<T>> futures, ChatRequestDeadline deadline) {
    List<T> values = new ArrayList<>();
    try {
      for (Future<T> future : futures) {
        Duration timeout = boundedTimeout(deadline);
        values.add(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
      }
      return values;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      cancelAll(futures);
      throw new IllegalStateException("병렬 추론 요청이 취소됐어요.", exception);
    } catch (ExecutionException exception) {
      cancelAll(futures);
      throw propagate(exception.getCause());
    } catch (TimeoutException exception) {
      cancelAll(futures);
      throw new IllegalStateException("병렬 추론 단계 응답 시간이 초과됐어요.", exception);
    }
  }

  private void cancelAll(List<? extends Future<?>> futures) {
    futures.forEach(future -> future.cancel(true));
  }

  private Duration boundedTimeout(ChatRequestDeadline deadline) {
    try {
      return deadline.boundedBy(properties.getStageTimeout());
    } catch (TimeoutException exception) {
      throw new IllegalStateException("관리자 챗봇 요청 기한이 지났어요.", exception);
    }
  }

  private RuntimeException propagate(Throwable cause) {
    return cause instanceof RuntimeException runtime
        ? runtime
        : new IllegalStateException("추론 단계를 완료하지 못했어요.", cause);
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
      int shardIndex, String earlyAnswer, List<QueryBinding> bindings) {}
}

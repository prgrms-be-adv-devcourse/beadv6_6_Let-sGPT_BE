package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.port.AdminChatInferencePort.ToolInvocation;
import com.openat.chat.application.port.AdminInitialToolPort;
import com.openat.chat.application.port.ChatEventSink;
import com.openat.chat.domain.query.InternalDataDomain;
import com.openat.chat.infrastructure.inference.tool.AdminDataTools;
import com.openat.chat.infrastructure.inference.tool.AdminToolExecutionContext;
import com.openat.chat.infrastructure.inference.tool.CryptoPriceTools;
import com.openat.chat.infrastructure.inference.tool.OperationContextTools;
import com.openat.chat.infrastructure.inference.tool.WeatherTools;
import com.openat.chat.infrastructure.inference.tool.WebSearchTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class InitialToolCallbackRegistry implements AdminInitialToolPort {

  private static final String SCHEMA_SELECTOR = "loadInternalDataSchemas";

  private final Map<String, ToolCallback> callbacks;
  private final ObjectMapper objectMapper;
  private final ExecutorService taskExecutor;
  private final ChatInferenceProperties properties;

  public InitialToolCallbackRegistry(
      AdminDataTools adminDataTools,
      CryptoPriceTools cryptoPriceTools,
      OperationContextTools operationContextTools,
      WeatherTools weatherTools,
      WebSearchTools webSearchTools,
      ObjectMapper objectMapper,
      @Qualifier("chatTaskExecutor") ExecutorService taskExecutor,
      ChatInferenceProperties properties) {
    Map<String, ToolCallback> registered = new LinkedHashMap<>();
    Arrays.stream(
            ToolCallbacks.from(
                adminDataTools,
                cryptoPriceTools,
                operationContextTools,
                weatherTools,
                webSearchTools))
        .forEach(callback -> registered.put(callback.getToolDefinition().name(), callback));
    this.callbacks = Map.copyOf(registered);
    this.objectMapper = objectMapper;
    this.taskExecutor = taskExecutor;
    this.properties = properties;
  }

  @Override
  public InitialToolResult execute(
      ChatCommand command,
      List<ToolInvocation> invocations,
      ChatEventSink sink,
      ChatRequestDeadline deadline) {
    Set<InternalDataDomain> domains = EnumSet.noneOf(InternalDataDomain.class);
    List<EvidenceSegment> evidence = new ArrayList<>();
    List<IndexedFuture> futures = new ArrayList<>();
    boolean selectionRequested = false;
    boolean selectionFailed = false;

    AdminToolExecutionContext executionContext =
        new AdminToolExecutionContext(command.requestId(), command.message(), sink, true);
    ToolContext toolContext =
        new ToolContext(Map.of(AdminToolExecutionContext.KEY, executionContext));

    for (int index = 0; index < invocations.size(); index++) {
      ToolInvocation invocation = invocations.get(index);
      String segmentId = "r1-t%02d".formatted(index + 1);
      if (SCHEMA_SELECTOR.equals(invocation.name())) {
        selectionRequested = true;
        Selection selection = parseSelection(invocation.arguments());
        domains.addAll(selection.domains());
        if (selection.failed()) {
          selectionFailed = true;
          evidence.add(failure(segmentId, "INTERNAL_SCHEMA_SELECTION", "내부 데이터 영역을 구조화하지 못했어요."));
        }
        continue;
      }

      Future<EvidenceSegment> future =
          taskExecutor.submit(() -> executeOne(segmentId, invocation, toolContext));
      futures.add(new IndexedFuture(index, future));
    }

    futures.sort(java.util.Comparator.comparingInt(IndexedFuture::index));
    evidence.addAll(await(futures, deadline));
    return new InitialToolResult(
        Set.copyOf(domains), List.copyOf(evidence), selectionRequested, selectionFailed);
  }

  private EvidenceSegment executeOne(
      String segmentId, ToolInvocation invocation, ToolContext toolContext) {
    ToolCallback callback = callbacks.get(invocation.name());
    if (callback == null) {
      return failure(segmentId, invocation.name(), "허용되지 않은 도구 호출이에요.");
    }
    try {
      String result = callback.call(invocation.arguments(), toolContext);
      return evidence(segmentId, invocation.name(), result);
    } catch (RuntimeException exception) {
      return failure(segmentId, invocation.name(), "도구 인자를 검증하거나 실행하지 못했어요.");
    }
  }

  private EvidenceSegment evidence(String id, String scope, String json) {
    try {
      JsonNode root = objectMapper.readTree(json);
      EvidenceSegment.Status status =
          enumValue(root.path("status").asText(), EvidenceSegment.Status.class);
      if (status == null) {
        status = EvidenceSegment.Status.FAILED;
      }
      String code = root.path("code").asText("");
      String message = root.path("message").asText("");
      List<String> limitations =
          status == EvidenceSegment.Status.SUCCESS || message.isBlank()
              ? List.of()
              : List.of((code.isBlank() ? "" : code + ": ") + message);
      JsonNode facts = root.get("data");
      return new EvidenceSegment(
          id, status, scope, facts, limitations, source(scope), observedAt(facts), false);
    } catch (RuntimeException exception) {
      return failure(id, scope, "도구 결과 형식을 읽지 못했어요.");
    }
  }

  private Selection parseSelection(String arguments) {
    try {
      JsonNode root = objectMapper.readTree(arguments);
      JsonNode domainsNode = root.get("domains");
      if (domainsNode == null || !domainsNode.isArray() || domainsNode.isEmpty()) {
        return new Selection(Set.of(), true);
      }
      Set<InternalDataDomain> domains = EnumSet.noneOf(InternalDataDomain.class);
      boolean failed = false;
      for (JsonNode value : domainsNode) {
        InternalDataDomain domain =
            value.isTextual() ? enumValue(value.asText(), InternalDataDomain.class) : null;
        if (domain == null) {
          failed = true;
        } else {
          domains.add(domain);
        }
      }
      return new Selection(Set.copyOf(domains), failed || domains.isEmpty());
    } catch (RuntimeException exception) {
      return new Selection(Set.of(), true);
    }
  }

  private List<EvidenceSegment> await(List<IndexedFuture> futures, ChatRequestDeadline deadline) {
    List<EvidenceSegment> results = new ArrayList<>();
    try {
      for (IndexedFuture indexed : futures) {
        Duration timeout = deadline.boundedBy(properties.getStageTimeout());
        results.add(indexed.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS));
      }
      return results;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      cancel(futures);
      throw new IllegalStateException("가벼운 도구 실행이 취소됐어요.", exception);
    } catch (ExecutionException exception) {
      cancel(futures);
      throw new IllegalStateException("가벼운 도구 실행을 완료하지 못했어요.", exception.getCause());
    } catch (TimeoutException exception) {
      cancel(futures);
      throw new IllegalStateException("가벼운 도구 실행 시간이 초과됐어요.", exception);
    }
  }

  private void cancel(List<IndexedFuture> futures) {
    futures.forEach(indexed -> indexed.future().cancel(true));
  }

  private EvidenceSegment failure(String id, String scope, String reason) {
    return new EvidenceSegment(
        id, EvidenceSegment.Status.FAILED, scope, null, List.of(reason), source(scope), "", false);
  }

  private String source(String toolName) {
    return switch (toolName) {
      case "getWeatherForecast" -> "Open-Meteo";
      case "getCryptoPrice" -> "CoinGecko";
      case "searchWeb" -> "Tavily";
      case "getOpenAtOperationsContext" -> "OPENAT 운영 문서";
      case "lookupOrder", "countExpiredPaymentPendingOrders" -> "OPENAT ai_read";
      default -> "";
    };
  }

  private String observedAt(JsonNode facts) {
    if (facts == null || facts.isNull()) {
      return "";
    }
    for (String field : List.of("asOf", "observedAt", "forecastDate")) {
      JsonNode value = facts.get(field);
      if (value != null && value.isTextual()) {
        return value.asText();
      }
    }
    return "";
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

  private record Selection(Set<InternalDataDomain> domains, boolean failed) {}

  private record IndexedFuture(int index, Future<EvidenceSegment> future) {}
}

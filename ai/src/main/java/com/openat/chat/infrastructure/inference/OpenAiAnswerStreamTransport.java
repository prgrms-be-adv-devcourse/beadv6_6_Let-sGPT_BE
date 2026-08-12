package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.exception.AdminChatExecutionException.Reason;
import com.openat.chat.application.port.ChatStreamClosedException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiAnswerStreamTransport {

  private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

  private final ChatInferenceProperties inferenceProperties;
  private final ResolvedConnectionProperties connectionProperties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public OpenAiAnswerStreamTransport(
      ChatInferenceProperties inferenceProperties,
      OpenAiCommonProperties commonProperties,
      OpenAiChatProperties chatProperties,
      ObjectMapper objectMapper) {
    this(
        inferenceProperties,
        OpenAiAutoConfigurationUtil.resolveCommonProperties(commonProperties, chatProperties),
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(inferenceProperties.getStageTimeout())
            .build());
  }

  OpenAiAnswerStreamTransport(
      ChatInferenceProperties inferenceProperties,
      ResolvedConnectionProperties connectionProperties,
      ObjectMapper objectMapper,
      HttpClient httpClient) {
    this.inferenceProperties = inferenceProperties;
    if (connectionProperties.getBaseUrl() == null
        || connectionProperties.getBaseUrl().isBlank()) {
      throw new IllegalStateException("관리자 챗봇 추론 주소가 필요해요.");
    }
    this.connectionProperties = connectionProperties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  public void stream(
      AnswerRequest answer,
      Consumer<String> chunkConsumer,
      Duration timeout) {
    HttpRequest request = request(answer, timeout);
    SseSession session = new SseSession(objectMapper, chunkConsumer);
    CompletableFuture<HttpResponse<Void>> responseFuture =
        httpClient.sendAsync(request, session.bodyHandler());
    responseFuture.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            session.fail(unwrap(failure));
          }
        });

    try {
      session.completion().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AdminChatExecutionException(
          Reason.CANCELLED, "답변 스트림이 취소됐어요.", exception);
    } catch (TimeoutException exception) {
      throw new AdminChatExecutionException(
          Reason.TIMEOUT, "답변 스트림 시간이 초과됐어요.", exception);
    } catch (ExecutionException exception) {
      throw propagate(unwrap(exception.getCause()));
    } finally {
      session.cancel();
      responseFuture.cancel(true);
    }
  }

  private HttpRequest request(AnswerRequest answer, Duration timeout) {
    String apiKey = connectionProperties.getApiKey();
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("관리자 챗봇 추론 API 키가 필요해요.");
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", answer.system()));
    messages.add(Map.of("role", "user", "content", answer.user()));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", inferenceProperties.getModel());
    body.put("messages", messages);
    body.put("stream", true);
    body.put("temperature", 0.0);
    body.put("max_tokens", answer.maxTokens());
    body.put("reasoning_effort", inferenceProperties.getReasoningEffort());
    body.put("store", false);

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(timeout)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    objectMapper.writeValueAsBytes(body)));
    connectionProperties.getCustomHeaders().forEach(builder::header);
    String organizationId = connectionProperties.getOrganizationId();
    if (organizationId != null && !organizationId.isBlank()) {
      builder.header("OpenAI-Organization", organizationId);
    }
    return builder.build();
  }

  private URI chatCompletionsUri() {
    String baseUrl = baseUrl();
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    return URI.create(baseUrl + CHAT_COMPLETIONS_PATH);
  }

  String baseUrl() {
    return connectionProperties.getBaseUrl().strip();
  }

  private RuntimeException propagate(Throwable failure) {
    if (failure instanceof ChatStreamClosedException streamClosedException) {
      return streamClosedException;
    }
    if (failure instanceof AdminChatExecutionException executionException) {
      return executionException;
    }
    if (failure instanceof HttpTimeoutException || failure instanceof TimeoutException) {
      return new AdminChatExecutionException(
          Reason.TIMEOUT, "답변 스트림 시간이 초과됐어요.", failure);
    }
    if (failure instanceof CancellationException) {
      return new AdminChatExecutionException(
          Reason.CANCELLED, "답변 스트림이 취소됐어요.", failure);
    }
    return failure instanceof RuntimeException runtimeException
        ? runtimeException
        : new IllegalStateException("답변 스트림을 완료하지 못했어요.", failure);
  }

  private Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  public record AnswerRequest(String system, String user, int maxTokens) {

    public AnswerRequest {
      if (system == null || system.isBlank() || user == null || user.isBlank()) {
        throw new IllegalArgumentException("최종 답변 프롬프트가 필요해요.");
      }
      if (maxTokens < 1) {
        throw new IllegalArgumentException("최종 답변 출력 토큰은 1 이상이어야 해요.");
      }
    }
  }

  private static final class SseSession implements Flow.Subscriber<String> {

    private final ObjectMapper objectMapper;
    private final Consumer<String> chunkConsumer;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
    private final StringBuilder eventData = new StringBuilder();
    private StreamState state = StreamState.STREAMING;
    private boolean firstLine = true;

    private SseSession(ObjectMapper objectMapper, Consumer<String> chunkConsumer) {
      this.objectMapper = objectMapper;
      this.chunkConsumer = chunkConsumer;
    }

    private HttpResponse.BodyHandler<Void> bodyHandler() {
      return responseInfo -> {
        int status = responseInfo.statusCode();
        if (status < 200 || status >= 300) {
          fail(new IllegalStateException("추론 서버가 HTTP " + status + "로 응답했어요."));
          return HttpResponse.BodySubscribers.discarding();
        }
        String contentType =
            responseInfo.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("text/event-stream")) {
          fail(new IllegalStateException("추론 서버가 SSE가 아닌 응답을 반환했어요."));
          return HttpResponse.BodySubscribers.discarding();
        }
        return HttpResponse.BodySubscribers.fromLineSubscriber(this);
      };
    }

    private CompletableFuture<Void> completion() {
      return completion;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      if (!subscription.compareAndSet(null, value)) {
        value.cancel();
        return;
      }
      if (completion.isDone()) {
        value.cancel();
        return;
      }
      value.request(1);
    }

    @Override
    public void onNext(String line) {
      if (completion.isDone()) {
        cancel();
        return;
      }
      try {
        acceptLine(line);
        Flow.Subscription current = subscription.get();
        if (!completion.isDone() && current != null) {
          current.request(1);
        }
      } catch (RuntimeException exception) {
        fail(exception);
      }
    }

    @Override
    public void onError(Throwable failure) {
      fail(failure);
    }

    @Override
    public void onComplete() {
      if (!completion.isDone()) {
        fail(new IllegalStateException("추론 서버가 [DONE] 없이 답변 스트림을 끝냈어요."));
      }
    }

    private void acceptLine(String rawLine) {
      String line = rawLine;
      if (firstLine) {
        firstLine = false;
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
          line = line.substring(1);
        }
      }
      if (line.isEmpty()) {
        dispatchEvent();
        return;
      }
      if (line.charAt(0) == ':') {
        return;
      }
      int separator = line.indexOf(':');
      String field = separator < 0 ? line : line.substring(0, separator);
      if (!"data".equals(field)) {
        return;
      }
      String value = separator < 0 ? "" : line.substring(separator + 1);
      if (value.startsWith(" ")) {
        value = value.substring(1);
      }
      if (!eventData.isEmpty()) {
        eventData.append('\n');
      }
      eventData.append(value);
    }

    private void dispatchEvent() {
      if (eventData.isEmpty()) {
        return;
      }
      String data = eventData.toString();
      eventData.setLength(0);
      if ("[DONE]".equals(data.strip())) {
        if (state != StreamState.STOPPED) {
          throw new IllegalStateException(
              "추론 서버가 stop 종료 이유 없이 [DONE]을 반환했어요.");
        }
        state = StreamState.COMPLETED;
        completion.complete(null);
        cancel();
        return;
      }
      acceptChunk(data);
    }

    private void acceptChunk(String data) {
      JsonNode root;
      try {
        root = objectMapper.readTree(data);
      } catch (RuntimeException exception) {
        throw new IllegalStateException("추론 서버가 잘못된 SSE JSON을 반환했어요.", exception);
      }
      if (root == null || !root.isObject()) {
        throw new IllegalStateException("추론 서버가 잘못된 SSE 청크를 반환했어요.");
      }
      if (root.has("error")) {
        throw new IllegalStateException("추론 서버가 스트림 오류 이벤트를 반환했어요.");
      }
      JsonNode choices = root.get("choices");
      if (choices == null || !choices.isArray()) {
        throw new IllegalStateException("추론 서버가 잘못된 choices를 반환했어요.");
      }
      if (choices.isEmpty()) {
        acceptUsage(root);
        return;
      }
      if (state != StreamState.STREAMING) {
        throw new IllegalStateException("추론 서버가 stop 종료 뒤 추가 choice를 반환했어요.");
      }
      if (choices.size() != 1) {
        throw new IllegalStateException("추론 서버가 예상하지 않은 choice 수를 반환했어요.");
      }
      JsonNode choice = choices.get(0);
      if (!choice.isObject()
          || !choice.path("index").isIntegralNumber()
          || choice.path("index").asInt() != 0) {
        throw new IllegalStateException("추론 서버가 잘못된 choice를 반환했어요.");
      }
      JsonNode reason = choice.get("finish_reason");
      String finishReason = null;
      if (reason != null && !reason.isNull()) {
        if (!reason.isTextual() || reason.asText().isBlank()) {
          throw new IllegalStateException("추론 서버가 잘못된 finish reason을 반환했어요.");
        }
        finishReason = reason.asText();
        if (!"stop".equalsIgnoreCase(finishReason)) {
          throw new IllegalStateException(
              "추론 서버가 stop 종료 이유가 아닌 값을 반환했어요: " + finishReason);
        }
      }
      JsonNode delta = choice.get("delta");
      if (delta == null || !delta.isObject()) {
        throw new IllegalStateException("추론 서버가 잘못된 delta를 반환했어요.");
      }
      JsonNode content = delta.get("content");
      if (content != null && !content.isNull() && !content.isTextual()) {
        throw new IllegalStateException("추론 서버가 잘못된 content를 반환했어요.");
      }
      if (content != null && content.isTextual() && !content.asText().isEmpty()) {
        chunkConsumer.accept(content.asText());
      }
      if (finishReason != null) {
        state = StreamState.STOPPED;
      }
    }

    private void acceptUsage(JsonNode root) {
      if (state != StreamState.STOPPED
          || !root.has("usage")
          || !root.get("usage").isObject()) {
        throw new IllegalStateException("추론 서버가 usage가 아닌 빈 choices를 반환했어요.");
      }
    }

    private void fail(Throwable failure) {
      if (completion.completeExceptionally(failure)) {
        cancel();
      }
    }

    private void cancel() {
      Flow.Subscription current = subscription.get();
      if (current != null) {
        current.cancel();
      }
    }

    private enum StreamState {
      STREAMING,
      STOPPED,
      COMPLETED
    }
  }
}

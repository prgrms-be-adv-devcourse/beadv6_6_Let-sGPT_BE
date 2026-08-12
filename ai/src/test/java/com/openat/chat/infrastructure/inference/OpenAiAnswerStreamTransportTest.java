package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.chat.application.exception.AdminChatExecutionException;
import com.openat.chat.application.exception.AdminChatExecutionException.Reason;
import com.openat.chat.application.port.ChatStreamClosedException;
import com.openat.chat.infrastructure.inference.OpenAiAnswerStreamTransport.AnswerRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("최종 답변 OpenAI SSE transport")
class OpenAiAnswerStreamTransportTest {

  private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
  private HttpServer server;
  private ExecutorService serverExecutor;
  private OpenAiAnswerStreamTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          HttpHandler current = handler.get();
          if (current == null) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
          }
          current.handle(exchange);
        });
    server.start();

    ChatInferenceProperties inferenceProperties = new ChatInferenceProperties();
    inferenceProperties.setModel("fixture-model");
    inferenceProperties.setReasoningEffort("none");
    OpenAiCommonProperties openAiProperties = new OpenAiCommonProperties();
    openAiProperties.setBaseUrl(serverBaseUrl());
    openAiProperties.setApiKey("fixture-api-key");
    transport =
        new OpenAiAnswerStreamTransport(
            inferenceProperties, openAiProperties, new OpenAiChatProperties(), objectMapper);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    serverExecutor.shutdownNow();
  }

  @Test
  @DisplayName("stop 청크와 실제 [DONE]을 모두 받으면 자연어 조각을 전달하고 완료한다")
  void stream_stopAndDone_completes() {
    // given
    handler.set(
        sse(
            data(chunk("첫", null)),
            data(chunk(" 답변", null)),
            data(chunk("", "stop")),
            data("[DONE]")));
    List<String> chunks = new ArrayList<>();

    // when
    transport.stream(answer(), chunks::add, Duration.ofSeconds(2));

    // then
    assertThat(chunks).containsExactly("첫", " 답변");
    assertThat(authorization.get()).isEqualTo("Bearer fixture-api-key");
    JsonNode request = objectMapper.readTree(requestBody.get());
    assertThat(request.get("model").asText()).isEqualTo("fixture-model");
    assertThat(request.get("stream").asBoolean()).isTrue();
    assertThat(request.get("store").asBoolean()).isFalse();
    assertThat(request.get("max_tokens").asInt()).isEqualTo(128);
    assertThat(request.get("reasoning_effort").asText()).isEqualTo("none");
    assertThat(request.get("messages")).hasSize(2);
  }

  @Test
  @DisplayName("Spring AI chat base URL override를 common URL보다 우선한다")
  void stream_chatBaseUrlOverride_usesResolvedSpringAiEndpoint() {
    // given
    ChatInferenceProperties inferenceProperties = new ChatInferenceProperties();
    inferenceProperties.setModel("fixture-model");
    OpenAiCommonProperties commonProperties = new OpenAiCommonProperties();
    commonProperties.setBaseUrl("https://unused.example.com/v1");
    commonProperties.setApiKey("fixture-api-key");
    OpenAiChatProperties chatProperties = new OpenAiChatProperties();
    chatProperties.setBaseUrl(serverBaseUrl());
    transport =
        new OpenAiAnswerStreamTransport(
            inferenceProperties, commonProperties, chatProperties, objectMapper);
    handler.set(sse(data(chunk("override", null)), data(chunk("", "stop")), data("[DONE]")));

    // when
    transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2));

    // then
    assertThat(transport.baseUrl()).isEqualTo(serverBaseUrl());
    assertThat(requestBody.get()).contains("fixture-model");
  }

  @Test
  @DisplayName("stop 청크 뒤 clean EOF가 와도 [DONE]이 없으면 부분 실패한다")
  void stream_stopWithoutDone_rejectsCleanEof() {
    // given
    handler.set(sse(data(chunk("잘린 답변", null)), data(chunk("", "stop"))));
    List<String> chunks = new ArrayList<>();

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), chunks::add, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("[DONE]");
    assertThat(chunks).containsExactly("잘린 답변");
  }

  @Test
  @DisplayName("[DONE]이 있어도 finish reason이 없으면 완결된 답변으로 인정하지 않는다")
  void stream_doneWithoutFinishReason_rejectsCompletion() {
    // given
    handler.set(sse(data(chunk("잘린 답변", null)), data("[DONE]")));

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stop 종료 이유");
  }

  @Test
  @DisplayName("length 종료는 [DONE]이 있어도 부분 실패한다")
  void stream_lengthFinishReason_rejectsCompletion() {
    // given
    handler.set(
        sse(
            data(chunk("토큰 한도로 잘린 답변", null)),
            data(chunk("", "length")),
            data("[DONE]")));

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stop 종료 이유");
  }

  @Test
  @DisplayName("잘못된 SSE JSON은 부분 답변을 정상 완료하지 않는다")
  void stream_malformedChunk_rejectsResponse() {
    // given
    handler.set(sse(data("{not-json}")));

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SSE JSON");
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "{\"choices\":{}}",
        "{\"choices\":[\"not-a-choice\"]}",
        "{\"choices\":[{\"index\":\"0\",\"delta\":{},\"finish_reason\":null}]}",
        "{\"choices\":[{\"index\":0,\"delta\":\"not-a-delta\",\"finish_reason\":null}]}",
        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":1},\"finish_reason\":null}]}"
      })
  @DisplayName("잘못된 choices·choice·delta 형식은 protocol 실패한다")
  void stream_invalidChoiceProtocol_rejectsResponse(String invalidChunk) {
    // given
    handler.set(sse(data(invalidChunk)));

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("stop 뒤 usage-only 빈 choices는 허용한다")
  void stream_usageOnlyChunkAfterStop_completes() {
    // given
    handler.set(
        sse(
            data(chunk("답변", null)),
            data(chunk("", "stop")),
            data("{\"choices\":[],\"usage\":{\"completion_tokens\":1}}"),
            data("[DONE]")));

    // when & then
    transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("usage가 아닌 빈 choices는 protocol 실패한다")
  void stream_emptyChoicesWithoutUsage_rejectsResponse() {
    // given
    handler.set(sse(data("{\"choices\":[]}")));

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("usage");
  }

  @Test
  @DisplayName("stop 뒤 content choice가 이어지면 protocol 실패한다")
  void stream_contentChoiceAfterStop_rejectsResponse() {
    // given
    handler.set(
        sse(
            data(chunk("답변", null)),
            data(chunk("", "stop")),
            data(chunk("추가 내용", null)),
            data("[DONE]")));

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stop 종료 뒤");
  }

  @Test
  @DisplayName("HTTP 오류 응답은 SSE 성공으로 변환하지 않는다")
  void stream_httpError_rejectsResponse() {
    // given
    handler.set(
        exchange -> {
          captureRequest(exchange);
          byte[] body =
              "{\"error\":{\"code\":\"upstream_unavailable\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(502, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    // when & then
    assertThatThrownBy(
            () -> transport.stream(answer(), ignored -> {}, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP 502");
  }

  @Test
  @DisplayName("사용자 연결 종료 예외는 원본을 보존하며 upstream을 취소한다")
  void stream_consumerClosed_preservesCancellation() {
    // given
    CountDownLatch upstreamCancelled = new CountDownLatch(1);
    CountDownLatch releaseResponse = new CountDownLatch(1);
    handler.set(streamUntilClientCloses(upstreamCancelled, releaseResponse));
    ChatStreamClosedException expected = new ChatStreamClosedException(null);

    try {
      // when & then
      assertThatThrownBy(
              () ->
                  transport.stream(
                      answer(),
                      ignored -> {
                        throw expected;
                      },
                      Duration.ofSeconds(2)))
          .isSameAs(expected);
      assertThat(upstreamCancelled.await(2, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    } finally {
      releaseResponse.countDown();
    }
  }

  @Test
  @DisplayName("응답 중단 상태가 제한 시간을 넘으면 typed timeout으로 upstream을 취소한다")
  void stream_stalledResponse_reportsTimeout() {
    // given
    CountDownLatch releaseResponse = new CountDownLatch(1);
    CountDownLatch upstreamCancelled = new CountDownLatch(1);
    handler.set(streamUntilClientCloses(upstreamCancelled, releaseResponse));

    try {
      // when & then
      assertThatThrownBy(
              () -> transport.stream(answer(), ignored -> {}, Duration.ofMillis(100)))
          .isInstanceOfSatisfying(
              AdminChatExecutionException.class,
              exception -> assertThat(exception.reason()).isEqualTo(Reason.TIMEOUT));
      assertThat(upstreamCancelled.await(2, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    } finally {
      releaseResponse.countDown();
    }
  }

  private HttpHandler streamUntilClientCloses(
      CountDownLatch upstreamCancelled, CountDownLatch releaseResponse) {
    return exchange -> {
      captureRequest(exchange);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      byte[] heartbeat = (":" + "x".repeat(16_384) + "\n\n").getBytes(StandardCharsets.UTF_8);
      try {
        exchange.getResponseBody().write(data(chunk("첫 조각", null)));
        exchange.getResponseBody().flush();
        while (!releaseResponse.await(5, TimeUnit.MILLISECONDS)) {
          exchange.getResponseBody().write(heartbeat);
          exchange.getResponseBody().flush();
        }
      } catch (IOException exception) {
        upstreamCancelled.countDown();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    };
  }

  private HttpHandler sse(byte[]... events) {
    return exchange -> {
      captureRequest(exchange);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
      exchange.sendResponseHeaders(200, 0);
      try {
        for (byte[] event : events) {
          exchange.getResponseBody().write(event);
          exchange.getResponseBody().flush();
        }
      } finally {
        exchange.close();
      }
    };
  }

  private void captureRequest(HttpExchange exchange) throws IOException {
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
  }

  private byte[] data(String value) {
    return ("data: " + value + "\n\n").getBytes(StandardCharsets.UTF_8);
  }

  private String chunk(String content, String finishReason) {
    String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
    return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
        + content
        + "\"},\"finish_reason\":"
        + finish
        + "}]}";
  }

  private AnswerRequest answer() {
    return new AnswerRequest("system prompt", "user prompt", 128);
  }

  private String serverBaseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }
}

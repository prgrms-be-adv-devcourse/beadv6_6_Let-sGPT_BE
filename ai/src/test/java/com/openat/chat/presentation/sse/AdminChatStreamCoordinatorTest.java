package com.openat.chat.presentation.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.ErrorPayload;
import com.openat.chat.application.port.ChatEventSink;
import com.openat.chat.application.service.AdminChatService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("관리자 챗봇 SSE 조정자")
class AdminChatStreamCoordinatorTest {

  private AdminChatService chatService;
  private ThreadPoolTaskExecutor executor;
  private ScheduledExecutorService scheduler;
  private ChatStreamProperties properties;
  private AdminChatStreamCoordinator coordinator;

  @BeforeEach
  void setUp() {
    chatService = mock(AdminChatService.class);
    executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.initialize();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    properties = new ChatStreamProperties();
    properties.setDeadline(Duration.ofSeconds(1));
    properties.setEmitterTimeout(Duration.ofSeconds(2));
    properties.setHeartbeatInterval(Duration.ofSeconds(1));
    coordinator =
        new AdminChatStreamCoordinator(
            chatService,
            executor,
            scheduler,
            properties,
            new ChatStreamMetrics(new SimpleMeterRegistry()),
            new ChatEventPayloadValidator(JsonMapper.builder().findAndAddModules().build()),
            Clock.systemUTC());
  }

  @AfterEach
  void tearDown() {
    executor.shutdown();
    scheduler.shutdownNow();
  }

  @Test
  @DisplayName("started 다음에 서비스의 스트리밍 이벤트를 순서대로 전달한다")
  void start_success_preservesEventOrder() throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    ChatCommand command = command();
    RecordingStreamSink sink = new RecordingStreamSink();
    doAnswer(
            invocation -> {
              ChatEventSink eventSink = invocation.getArgument(1);
              eventSink.emit(ChatStreamEvent.delta(command.requestId(), "안녕"));
              eventSink.terminate(ChatStreamEvent.done(command.requestId()));
              completed.countDown();
              return null;
            })
        .when(chatService)
        .execute(any(), any(), any());

    coordinator.start(command, sink);

    assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(sink.names()).containsExactly("started", "delta", "done");
  }

  @Test
  @DisplayName("마감 시간을 넘기면 작업을 중단하고 재시도 가능한 timeout으로 종료한다")
  void start_deadline_terminatesTimeout() throws Exception {
    properties.setDeadline(Duration.ofMillis(30));
    CountDownLatch serviceStarted = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    ChatCommand command = command();
    RecordingStreamSink sink = new RecordingStreamSink();
    doAnswer(
            invocation -> {
              serviceStarted.countDown();
              try {
                Thread.sleep(5_000);
              } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return null;
            })
        .when(chatService)
        .execute(any(), any(), any());

    coordinator.start(command, sink);

    assertThat(serviceStarted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(sink.terminated.await(1, TimeUnit.SECONDS)).isTrue();
    ErrorPayload error = (ErrorPayload) sink.events.getLast().data();
    assertThat(error.code()).isEqualTo("CHAT_TIMEOUT");
    assertThat(error.retryable()).isTrue();
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("실행기 대기 시간도 요청 마감 시간에 포함한다")
  void start_queuedWork_expiresBeforeServiceExecution() throws Exception {
    properties.setDeadline(Duration.ofMillis(50));
    CountDownLatch blockerStarted = new CountDownLatch(1);
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    executor.submit(
        () -> {
          blockerStarted.countDown();
          try {
            releaseBlocker.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(blockerStarted.await(1, TimeUnit.SECONDS)).isTrue();

    RecordingStreamSink sink = new RecordingStreamSink();
    coordinator.start(command(), sink);

    assertThat(sink.terminated.await(1, TimeUnit.SECONDS)).isTrue();
    ErrorPayload error = (ErrorPayload) sink.events.getLast().data();
    assertThat(error.code()).isEqualTo("CHAT_TIMEOUT");
    releaseBlocker.countDown();
    verifyNoInteractions(chatService);
  }

  private ChatCommand command() {
    return new ChatCommand(UUID.randomUUID(), "admin", Set.of("ROLE_ADMIN"), "오늘 주문 수 알려줘");
  }

  private static final class RecordingStreamSink implements ChatStreamSink {

    private final List<ChatStreamEvent> events = new ArrayList<>();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private boolean closed;
    private boolean partial;

    @Override
    public synchronized void emit(ChatStreamEvent event) {
      events.add(event);
      partial |= "delta".equals(event.name());
    }

    @Override
    public synchronized boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory) {
      if (closed) {
        return false;
      }
      events.add(eventFactory.apply(partial));
      closed = true;
      terminated.countDown();
      return true;
    }

    @Override
    public void heartbeat() {}

    @Override
    public synchronized void close() {
      closed = true;
    }

    @Override
    public synchronized boolean isClosed() {
      return closed;
    }

    synchronized List<String> names() {
      return events.stream().map(ChatStreamEvent::name).toList();
    }
  }
}

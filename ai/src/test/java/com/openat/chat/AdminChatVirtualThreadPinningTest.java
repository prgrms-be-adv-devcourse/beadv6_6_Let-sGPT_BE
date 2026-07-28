package com.openat.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.infrastructure.config.ChatQueryDataSourceProperties;
import com.openat.chat.infrastructure.persistence.ReadModelStartupVerifier;
import com.openat.chat.presentation.sse.SseEmitterChatEventSink;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("관리자 챗봇 가상 스레드 피닝")
class AdminChatVirtualThreadPinningTest {

  private static final String PINNED_EVENT = "jdk.VirtualThreadPinned";
  private static final Duration BLOCKING_DURATION = Duration.ofMillis(100);

  @TempDir Path tempDirectory;

  @Test
  @DisplayName("SSE 전송이 블로킹되어도 carrier 스레드를 피닝하지 않는다")
  void sseSend_doesNotPinCarrierThread() throws Exception {
    SseEmitterChatEventSink sink = new SseEmitterChatEventSink(new BlockingSseEmitter());

    assertNoPinning(
        "admin-chat-sse-pinning-test", () -> sink.emit(ChatStreamEvent.started(UUID.randomUUID())));
  }

  @Test
  @DisplayName("read-model 검증이 블로킹되어도 carrier 스레드를 피닝하지 않는다")
  void readModelVerification_doesNotPinCarrierThread() throws Exception {
    NamedParameterJdbcTemplate namedParameterJdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    given(namedParameterJdbcTemplate.getJdbcTemplate()).willReturn(jdbcTemplate);
    doAnswer(
            invocation -> {
              Thread.sleep(BLOCKING_DURATION.toMillis());
              throw new IllegalStateException("read-model unavailable");
            })
        .when(jdbcTemplate)
        .execute(ArgumentMatchers.<ConnectionCallback<Object>>any());

    ReadModelStartupVerifier verifier =
        new ReadModelStartupVerifier(namedParameterJdbcTemplate, configuredProperties());

    assertNoPinning("admin-chat-read-model-pinning-test", verifier::verifyNow);
  }

  private void assertNoPinning(String threadName, Runnable action) throws Exception {
    Path recordingPath = tempDirectory.resolve(threadName + ".jfr");
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Object pinningMonitor = new Object();
    long virtualThreadId;
    long controlThreadId;

    try (Recording recording = new Recording()) {
      recording.enable(PINNED_EVENT).withThreshold(Duration.ZERO);
      recording.start();

      Thread virtualThread =
          Thread.ofVirtual()
              .name(threadName)
              .start(
                  () -> {
                    try {
                      action.run();
                    } catch (Throwable throwable) {
                      failure.set(throwable);
                    }
                  });
      virtualThread.join();
      virtualThreadId = virtualThread.threadId();

      Thread controlThread =
          Thread.ofVirtual().name(threadName + "-control").start(() -> pinCarrier(pinningMonitor));
      controlThread.join();
      controlThreadId = controlThread.threadId();

      recording.stop();
      recording.dump(recordingPath);
    }

    List<RecordedEvent> recordedEvents = RecordingFile.readAllEvents(recordingPath);

    assertThat(failure.get()).isNull();
    assertThat(pinnedEvents(recordedEvents, controlThreadId)).isNotEmpty();
    assertThat(pinnedEvents(recordedEvents, virtualThreadId)).isEmpty();
  }

  private List<RecordedEvent> pinnedEvents(List<RecordedEvent> recordedEvents, long threadId) {
    return recordedEvents.stream()
        .filter(event -> PINNED_EVENT.equals(event.getEventType().getName()))
        .filter(event -> event.getThread() != null)
        .filter(event -> event.getThread().getJavaThreadId() == threadId)
        .toList();
  }

  private void pinCarrier(Object monitor) {
    synchronized (monitor) {
      try {
        Thread.sleep(BLOCKING_DURATION.toMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    }
  }

  private ChatQueryDataSourceProperties configuredProperties() {
    ChatQueryDataSourceProperties properties = new ChatQueryDataSourceProperties();
    properties.setEnabled(true);
    properties.setUrl("jdbc:postgresql://localhost/openat");
    properties.setPassword("password");
    return properties;
  }

  private static final class BlockingSseEmitter extends SseEmitter {

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      try {
        Thread.sleep(BLOCKING_DURATION.toMillis());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException(exception);
      }
    }
  }
}

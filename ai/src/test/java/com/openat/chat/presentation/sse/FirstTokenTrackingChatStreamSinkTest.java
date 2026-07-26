package com.openat.chat.presentation.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.openat.chat.application.dto.ChatStreamEvent;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("첫 토큰 전송 지표 sink")
class FirstTokenTrackingChatStreamSinkTest {

  @Test
  @DisplayName("delta 전송에 성공한 뒤에만 첫 토큰을 기록한다")
  void emit_success_recordsFirstTokenAfterDelivery() {
    RecordingStreamSink delegate = new RecordingStreamSink();
    AtomicInteger firstTokens = new AtomicInteger();
    FirstTokenTrackingChatStreamSink sink =
        new FirstTokenTrackingChatStreamSink(delegate, firstTokens::incrementAndGet);

    sink.emit(ChatStreamEvent.delta(UUID.randomUUID(), "안녕"));
    sink.emit(ChatStreamEvent.delta(UUID.randomUUID(), "하세요"));

    assertThat(delegate.events).hasSize(2);
    assertThat(firstTokens).hasValue(1);
  }

  @Test
  @DisplayName("delta 전송이 실패하면 첫 토큰 성공으로 기록하지 않는다")
  void emit_failure_doesNotRecordFirstToken() {
    ChatStreamSink delegate = mock(ChatStreamSink.class);
    ChatStreamEvent delta = ChatStreamEvent.delta(UUID.randomUUID(), "안녕");
    AtomicInteger firstTokens = new AtomicInteger();
    doThrow(new IllegalStateException("send failed")).when(delegate).emit(delta);
    FirstTokenTrackingChatStreamSink sink =
        new FirstTokenTrackingChatStreamSink(delegate, firstTokens::incrementAndGet);

    assertThatThrownBy(() -> sink.emit(delta)).isInstanceOf(IllegalStateException.class);
    assertThat(firstTokens).hasValue(0);
  }

  private static final class RecordingStreamSink implements ChatStreamSink {

    private final java.util.List<ChatStreamEvent> events = new java.util.ArrayList<>();

    @Override
    public void emit(ChatStreamEvent event) {
      events.add(event);
    }

    @Override
    public boolean terminate(java.util.function.Function<Boolean, ChatStreamEvent> eventFactory) {
      return false;
    }

    @Override
    public void heartbeat() {}

    @Override
    public void close() {}

    @Override
    public boolean isClosed() {
      return false;
    }
  }
}

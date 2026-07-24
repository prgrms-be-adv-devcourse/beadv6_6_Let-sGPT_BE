package com.openat.chat.presentation.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.port.ChatStreamClosedException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@DisplayName("SSE 채팅 이벤트 sink")
class SseEmitterChatEventSinkTest {

  @Test
  @DisplayName("첫 자연어 조각 뒤 오류는 부분 응답으로 한 번만 종료한다")
  void terminate_afterDelta_marksPartialAndClosesOnce() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    AtomicBoolean partial = new AtomicBoolean(false);
    SseEmitterChatEventSink sink = new SseEmitterChatEventSink(emitter);
    UUID requestId = UUID.randomUUID();

    sink.emit(ChatStreamEvent.delta(requestId, "일부 답변"));
    boolean first =
        sink.terminate(
            emitted -> {
              partial.set(emitted);
              return ChatStreamEvent.error(
                  requestId, "CHAT_INFERENCE_UNAVAILABLE", "중단", true, emitted);
            });
    boolean second = sink.terminate(ChatStreamEvent.done(requestId));

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(partial).isTrue();
    assertThat(sink.isClosed()).isTrue();
    verify(emitter).complete();
  }

  @Test
  @DisplayName("전송 실패 뒤에는 추가 이벤트를 허용하지 않는다")
  void emit_sendFailure_closesSink() throws Exception {
    SseEmitter emitter = mock(SseEmitter.class);
    doThrow(new IOException("closed")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
    SseEmitterChatEventSink sink = new SseEmitterChatEventSink(emitter);

    assertThatThrownBy(() -> sink.emit(ChatStreamEvent.started(UUID.randomUUID())))
        .isInstanceOf(ChatStreamClosedException.class);
    assertThatThrownBy(() -> sink.emit(ChatStreamEvent.started(UUID.randomUUID())))
        .isInstanceOf(ChatStreamClosedException.class);
    verify(emitter, times(1)).completeWithError(any());
  }
}

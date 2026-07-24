package com.openat.chat.presentation.sse;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.DeltaPayload;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

final class FirstTokenTrackingChatStreamSink implements ChatStreamSink {

  private final ChatStreamSink delegate;
  private final Runnable firstTokenListener;
  private final AtomicBoolean firstTokenObserved = new AtomicBoolean(false);

  FirstTokenTrackingChatStreamSink(ChatStreamSink delegate, Runnable firstTokenListener) {
    this.delegate = delegate;
    this.firstTokenListener = firstTokenListener;
  }

  @Override
  public void emit(ChatStreamEvent event) {
    delegate.emit(event);
    if (isFirstToken(event) && firstTokenObserved.compareAndSet(false, true)) {
      firstTokenListener.run();
    }
  }

  @Override
  public boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory) {
    return delegate.terminate(eventFactory);
  }

  @Override
  public void heartbeat() {
    delegate.heartbeat();
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  private boolean isFirstToken(ChatStreamEvent event) {
    return "delta".equals(event.name())
        && event.data() instanceof DeltaPayload delta
        && delta.text() != null
        && !delta.text().isBlank();
  }
}

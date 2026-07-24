package com.openat.chat.presentation.sse;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.port.ChatStreamClosedException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseEmitterChatEventSink implements ChatStreamSink {

  private final SseEmitter emitter;
  private final Consumer<ChatStreamEvent> payloadValidator;
  private final Consumer<ChatStreamEvent> terminalListener;
  private final Runnable closeListener;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean partialResponse = new AtomicBoolean(false);
  private final Object sendLock = new Object();

  public SseEmitterChatEventSink(SseEmitter emitter) {
    this(emitter, ignored -> {}, ignored -> {}, () -> {});
  }

  SseEmitterChatEventSink(
      SseEmitter emitter,
      Consumer<ChatStreamEvent> payloadValidator,
      Consumer<ChatStreamEvent> terminalListener,
      Runnable closeListener) {
    this.emitter = emitter;
    this.payloadValidator = payloadValidator;
    this.terminalListener = terminalListener;
    this.closeListener = closeListener;
  }

  @Override
  public void emit(ChatStreamEvent event) {
    if (closed.get()) {
      throw new ChatStreamClosedException(null);
    }
    synchronized (sendLock) {
      if (closed.get()) {
        throw new ChatStreamClosedException(null);
      }
      try {
        payloadValidator.accept(event);
        emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
        if (isPartialResult(event.name())) {
          partialResponse.set(true);
        }
      } catch (IOException | IllegalStateException exception) {
        closeWithError(exception);
        throw new ChatStreamClosedException(exception);
      }
    }
  }

  @Override
  public boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory) {
    synchronized (sendLock) {
      if (closed.get()) {
        return false;
      }
      try {
        ChatStreamEvent event = eventFactory.apply(partialResponse.get());
        payloadValidator.accept(event);
        emitter.send(SseEmitter.event().name(event.name()).data(event.data()));
        closed.set(true);
        terminalListener.accept(event);
        emitter.complete();
        return true;
      } catch (IOException | IllegalStateException exception) {
        closeWithError(exception);
        return false;
      }
    }
  }

  @Override
  public void heartbeat() {
    if (closed.get()) {
      return;
    }
    synchronized (sendLock) {
      if (closed.get()) {
        return;
      }
      try {
        emitter.send(SseEmitter.event().comment("keep-alive"));
      } catch (IOException | IllegalStateException exception) {
        closeWithError(exception);
      }
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      closeListener.run();
    }
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }

  private void closeWithError(Throwable exception) {
    if (closed.compareAndSet(false, true)) {
      closeListener.run();
      emitter.completeWithError(exception);
    }
  }

  private boolean isPartialResult(String eventName) {
    return "delta".equals(eventName);
  }
}

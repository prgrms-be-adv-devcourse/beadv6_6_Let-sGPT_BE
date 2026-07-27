package com.openat.chat.presentation.sse;

import com.openat.chat.application.dto.ChatCommand;
import com.openat.chat.application.dto.ChatRequestDeadline;
import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.port.ChatStreamClosedException;
import com.openat.chat.application.service.AdminChatService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AdminChatStreamCoordinator {

  private final AdminChatService chatService;
  private final ThreadPoolTaskExecutor inferenceExecutor;
  private final ScheduledExecutorService scheduler;
  private final ChatStreamProperties properties;
  private final ChatStreamMetrics metrics;
  private final ChatEventPayloadValidator payloadValidator;
  private final Clock clock;

  public AdminChatStreamCoordinator(
      AdminChatService chatService,
      @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor inferenceExecutor,
      ScheduledExecutorService scheduler,
      ChatStreamProperties properties,
      ChatStreamMetrics metrics,
      ChatEventPayloadValidator payloadValidator,
      Clock clock) {
    this.chatService = chatService;
    this.inferenceExecutor = inferenceExecutor;
    this.scheduler = scheduler;
    this.properties = properties;
    this.metrics = metrics;
    this.payloadValidator = payloadValidator;
    this.clock = clock;
  }

  public SseEmitter open(ChatCommand command) {
    SseEmitter emitter = new SseEmitter(properties.getEmitterTimeout().toMillis());
    return open(command, emitter);
  }

  SseEmitter open(ChatCommand command, SseEmitter emitter) {
    ChatStreamMetrics.StreamObservation observation = metrics.opened();
    SseEmitterChatEventSink emitterSink =
        new SseEmitterChatEventSink(
            emitter, payloadValidator::validate, observation::terminal, observation::cancelled);
    ChatStreamSink sink =
        new FirstTokenTrackingChatStreamSink(emitterSink, observation::firstToken);
    AtomicReference<StreamControl> controlReference = new AtomicReference<>();
    Runnable releaseResources =
        () -> {
          sink.close();
          StreamControl control = controlReference.get();
          if (control != null) {
            control.cancelAll();
          }
        };

    emitter.onCompletion(releaseResources);
    emitter.onTimeout(
        () -> {
          observation.timeout();
          releaseResources.run();
          emitter.complete();
        });
    emitter.onError(ignored -> releaseResources.run());

    StreamControl control = start(command, sink, observation);
    controlReference.set(control);
    if (sink.isClosed()) {
      control.cancelAll();
    }
    return emitter;
  }

  StreamControl start(ChatCommand command, ChatStreamSink sink) {
    return start(command, sink, null);
  }

  private StreamControl start(
      ChatCommand command, ChatStreamSink sink, ChatStreamMetrics.StreamObservation observation) {
    StreamControl control = new StreamControl();
    try {
      sink.emit(ChatStreamEvent.started(command.requestId()));

      long heartbeatMillis = properties.getHeartbeatInterval().toMillis();
      ScheduledFuture<?> heartbeat =
          scheduler.scheduleAtFixedRate(
              sink::heartbeat, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
      control.attachHeartbeat(heartbeat);

      Duration deadline = properties.getDeadline();
      Instant absoluteDeadline = clock.instant().plus(deadline);
      ScheduledFuture<?> deadlineTask =
          scheduler.schedule(
              () -> timeout(command, sink, control), deadline.toMillis(), TimeUnit.MILLISECONDS);
      control.attachDeadline(deadlineTask);

      Future<?> work =
          inferenceExecutor.submit(
              () -> {
                try {
                  ChatRequestDeadline requestDeadline =
                      new ChatRequestDeadline(absoluteDeadline, clock);
                  chatService.execute(command, sink, requestDeadline);
                } finally {
                  control.finishWork();
                }
              });
      control.attachWork(work);
      return control;
    } catch (ChatStreamClosedException ignored) {
      control.cancelAll();
      return control;
    } catch (RejectedExecutionException exception) {
      rejectBusy(command, sink, control);
      return control;
    }
  }

  private void timeout(ChatCommand command, ChatStreamSink sink, StreamControl control) {
    boolean terminated =
        sink.terminate(
            partial ->
                ChatStreamEvent.error(
                    command.requestId(),
                    "CHAT_TIMEOUT",
                    "처리 시간이 길어져 요청을 종료했어요. 다시 시도해 줘.",
                    true,
                    partial));
    if (terminated) {
      control.cancelAll();
    } else {
      control.finishWork();
    }
  }

  private void rejectBusy(ChatCommand command, ChatStreamSink sink, StreamControl control) {
    sink.terminate(
        ChatStreamEvent.error(
            command.requestId(), "CHAT_BUSY", "현재 처리 중인 요청이 많아요. 잠시 후 다시 시도해 줘.", true, false));
    control.cancelAll();
  }

  static final class StreamControl {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Future<?>> work = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();

    static StreamControl completed() {
      StreamControl control = new StreamControl();
      control.cancelled.set(true);
      return control;
    }

    void attachWork(Future<?> future) {
      attach(work, future, true);
    }

    void attachHeartbeat(ScheduledFuture<?> future) {
      attach(heartbeat, future, false);
    }

    void attachDeadline(ScheduledFuture<?> future) {
      attach(deadline, future, false);
    }

    void finishWork() {
      cancel(heartbeat.get(), false);
      cancel(deadline.get(), false);
    }

    void cancelAll() {
      cancelled.set(true);
      cancel(heartbeat.get(), false);
      cancel(deadline.get(), false);
      cancel(work.get(), true);
    }

    private <T extends Future<?>> void attach(
        AtomicReference<T> reference, T future, boolean mayInterrupt) {
      reference.set(future);
      if (cancelled.get()) {
        cancel(future, mayInterrupt);
      }
    }

    private void cancel(Future<?> future, boolean mayInterrupt) {
      if (future != null && !future.isDone()) {
        future.cancel(mayInterrupt);
      }
    }
  }
}

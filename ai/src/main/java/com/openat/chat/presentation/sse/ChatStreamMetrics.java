package com.openat.chat.presentation.sse;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.ErrorPayload;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ChatStreamMetrics {

  private final MeterRegistry meterRegistry;
  private final AtomicInteger activeStreams = new AtomicInteger();

  public ChatStreamMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    Gauge.builder("ai.chat.stream.active", activeStreams, AtomicInteger::get)
        .description("Current active admin chat SSE streams")
        .register(meterRegistry);
  }

  StreamObservation opened() {
    meterRegistry.counter("ai.chat.stream.opened").increment();
    activeStreams.incrementAndGet();
    return new StreamObservation(meterRegistry, activeStreams);
  }

  enum TerminalOutcome {
    COMPLETED,
    ERROR,
    BUSY,
    TIMEOUT,
    CANCELLED
  }

  static final class StreamObservation {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeStreams;
    private final Timer.Sample duration;
    private final Timer.Sample firstTokenDuration;
    private final AtomicBoolean firstToken = new AtomicBoolean(false);
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    private StreamObservation(MeterRegistry meterRegistry, AtomicInteger activeStreams) {
      this.meterRegistry = meterRegistry;
      this.activeStreams = activeStreams;
      duration = Timer.start(meterRegistry);
      firstTokenDuration = Timer.start(meterRegistry);
    }

    void firstToken() {
      if (firstToken.compareAndSet(false, true)) {
        firstTokenDuration.stop(meterRegistry.timer("ai.chat.stream.first_token"));
      }
    }

    void terminal(ChatStreamEvent event) {
      if ("done".equals(event.name())) {
        finish(TerminalOutcome.COMPLETED, null);
        return;
      }
      if (event.data() instanceof ErrorPayload payload) {
        TerminalOutcome outcome = errorOutcome(payload.code());
        finish(outcome, outcome == TerminalOutcome.ERROR ? errorReason(payload.code()) : null);
        return;
      }
      finish(TerminalOutcome.ERROR, ErrorReason.OTHER);
    }

    void timeout() {
      finish(TerminalOutcome.TIMEOUT, null);
    }

    void cancelled() {
      finish(TerminalOutcome.CANCELLED, null);
    }

    private TerminalOutcome errorOutcome(String errorCode) {
      if ("CHAT_BUSY".equals(errorCode)) {
        return TerminalOutcome.BUSY;
      }
      if ("CHAT_TIMEOUT".equals(errorCode) || "CHAT_FIRST_TOKEN_TIMEOUT".equals(errorCode)) {
        return TerminalOutcome.TIMEOUT;
      }
      return TerminalOutcome.ERROR;
    }

    private ErrorReason errorReason(String errorCode) {
      if ("SECURITY_POLICY_REJECTED".equals(errorCode)) {
        return ErrorReason.SECURITY_POLICY;
      }
      if (errorCode != null && errorCode.startsWith("CHAT_INFERENCE")) {
        return ErrorReason.INFERENCE;
      }
      if ("CHAT_EMPTY_RESPONSE".equals(errorCode)) {
        return ErrorReason.EMPTY_RESPONSE;
      }
      if ("CHAT_REQUEST_REJECTED".equals(errorCode)) {
        return ErrorReason.REQUEST_REJECTED;
      }
      return ErrorReason.OTHER;
    }

    private void finish(TerminalOutcome outcome, ErrorReason errorReason) {
      if (!terminal.compareAndSet(false, true)) {
        return;
      }
      activeStreams.decrementAndGet();
      String outcomeTag = outcome.name().toLowerCase(Locale.ROOT);
      meterRegistry.counter("ai.chat.stream.terminal", "outcome", outcomeTag).increment();
      duration.stop(meterRegistry.timer("ai.chat.stream.duration"));
      if (errorReason != null) {
        meterRegistry.counter("ai.chat.stream.error", "reason", errorReason.tagValue).increment();
      }
    }
  }

  private enum ErrorReason {
    SECURITY_POLICY("security_policy"),
    INFERENCE("inference"),
    EMPTY_RESPONSE("empty_response"),
    REQUEST_REJECTED("request_rejected"),
    OTHER("other");

    private final String tagValue;

    ErrorReason(String tagValue) {
      this.tagValue = tagValue;
    }
  }
}

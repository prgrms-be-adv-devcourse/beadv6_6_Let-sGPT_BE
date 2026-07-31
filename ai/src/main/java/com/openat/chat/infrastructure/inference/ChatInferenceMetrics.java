package com.openat.chat.infrastructure.inference;

import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.application.exception.AdminChatExecutionException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ChatInferenceMetrics {

  private final MeterRegistry meterRegistry;

  public ChatInferenceMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordStage(Stage stage, Outcome outcome, long startedAtNanos) {
    Timer.builder("ai.chat.inference.stage")
        .description("Admin chat inference stage duration")
        .tag("stage", stage.tagValue)
        .tag("outcome", outcome.tagValue)
        .register(meterRegistry)
        .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)));
  }

  public void recordInputTokens(Stage stage, int tokens) {
    DistributionSummary.builder("ai.chat.inference.input.tokens")
        .description("Estimated input tokens before an admin chat model call")
        .baseUnit("tokens")
        .tag("stage", stage.tagValue)
        .register(meterRegistry)
        .record(tokens);
  }

  public void recordEvidence(EvidenceStage stage, List<EvidenceSegment> evidence) {
    evidence.forEach(
        segment ->
            meterRegistry
                .counter(
                    "ai.chat.evidence",
                    "stage",
                    stage.tagValue,
                    "outcome",
                    segment.status().name().toLowerCase(Locale.ROOT))
                .increment());
  }

  public void recordSelection(int toolCount, int domainCount) {
    selectionSummary("tool").record(toolCount);
    selectionSummary("domain").record(domainCount);
  }

  public Outcome outcome(AdminChatExecutionException exception) {
    return switch (exception.reason()) {
      case INPUT_BUDGET_EXCEEDED -> Outcome.INPUT_BUDGET_EXCEEDED;
      case TIMEOUT -> Outcome.TIMEOUT;
      case BUSY -> Outcome.BUSY;
      case CANCELLED -> Outcome.CANCELLED;
      case SELECTION_FAILED -> Outcome.ERROR;
    };
  }

  private DistributionSummary selectionSummary(String type) {
    return DistributionSummary.builder("ai.chat.selection.count")
        .description("Selected tools and internal data domains per admin chat request")
        .baseUnit("items")
        .tag("type", type)
        .register(meterRegistry);
  }

  public enum Stage {
    ROUTING("routing"),
    BINDING("binding"),
    ANSWER("answer");

    private final String tagValue;

    Stage(String tagValue) {
      this.tagValue = tagValue;
    }
  }

  public enum EvidenceStage {
    INITIAL_TOOL("initial_tool"),
    ANALYTICS("analytics");

    private final String tagValue;

    EvidenceStage(String tagValue) {
      this.tagValue = tagValue;
    }
  }

  public enum Outcome {
    SUCCESS("success"),
    INPUT_BUDGET_EXCEEDED("input_budget_exceeded"),
    TIMEOUT("timeout"),
    BUSY("busy"),
    CANCELLED("cancelled"),
    ERROR("error");

    private final String tagValue;

    Outcome(String tagValue) {
      this.tagValue = tagValue;
    }
  }
}

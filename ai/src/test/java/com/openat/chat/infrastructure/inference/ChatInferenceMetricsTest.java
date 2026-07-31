package com.openat.chat.infrastructure.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.application.dto.EvidenceSegment;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.EvidenceStage;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.Outcome;
import com.openat.chat.infrastructure.inference.ChatInferenceMetrics.Stage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 챗봇 추론 지표")
class ChatInferenceMetricsTest {

  @Test
  @DisplayName("단계·입력 토큰·근거 결과와 선택 수를 낮은 카디널리티로 기록한다")
  void metrics_recordBoundedContractDimensions() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ChatInferenceMetrics metrics = new ChatInferenceMetrics(registry);
    EvidenceSegment success =
        new EvidenceSegment(
            "segment-1",
            EvidenceSegment.Status.SUCCESS,
            "scope",
            null,
            List.of(),
            "",
            "",
            false);
    EvidenceSegment failure =
        new EvidenceSegment(
            "segment-2",
            EvidenceSegment.Status.FAILED,
            "scope",
            null,
            List.of("실패"),
            "",
            "",
            false);

    metrics.recordStage(Stage.ROUTING, Outcome.SUCCESS, System.nanoTime());
    metrics.recordInputTokens(Stage.ROUTING, 1_234);
    metrics.recordEvidence(EvidenceStage.INITIAL_TOOL, List.of(success, failure));
    metrics.recordSelection(3, 1);

    assertThat(
            registry
                .get("ai.chat.inference.stage")
                .tags("stage", "routing", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.chat.inference.input.tokens")
                .tag("stage", "routing")
                .summary()
                .totalAmount())
        .isEqualTo(1_234);
    assertThat(
            registry
                .get("ai.chat.evidence")
                .tags("stage", "initial_tool", "outcome", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.chat.evidence")
                .tags("stage", "initial_tool", "outcome", "failed")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.chat.selection.count")
                .tag("type", "tool")
                .summary()
                .totalAmount())
        .isEqualTo(3);
    assertThat(
            registry
                .get("ai.chat.selection.count")
                .tag("type", "domain")
                .summary()
                .totalAmount())
        .isEqualTo(1);
  }
}

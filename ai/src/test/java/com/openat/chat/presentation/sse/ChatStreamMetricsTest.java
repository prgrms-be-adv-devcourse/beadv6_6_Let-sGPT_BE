package com.openat.chat.presentation.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.application.dto.ChatStreamEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("관리자 챗봇 스트림 지표")
class ChatStreamMetricsTest {

  @Test
  @DisplayName("완료 스트림은 활성 수와 첫 토큰·종료 지표를 한 번만 기록한다")
  void observation_completed_recordsMetricsOnce() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ChatStreamMetrics metrics = new ChatStreamMetrics(registry);
    ChatStreamMetrics.StreamObservation observation = metrics.opened();
    UUID requestId = UUID.randomUUID();

    observation.firstToken();
    observation.firstToken();
    observation.terminal(ChatStreamEvent.done(requestId));
    observation.terminal(ChatStreamEvent.done(requestId));

    assertThat(registry.get("ai.chat.stream.active").gauge().value()).isZero();
    assertThat(registry.get("ai.chat.stream.first_token").timer().count()).isEqualTo(1);
    assertThat(
            registry.get("ai.chat.stream.terminal").tag("outcome", "completed").counter().count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("보안 오류는 사유를 낮은 카디널리티 태그로 기록한다")
  void observation_securityError_recordsReason() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ChatStreamMetrics.StreamObservation observation = new ChatStreamMetrics(registry).opened();

    observation.terminal(
        ChatStreamEvent.error(UUID.randomUUID(), "SECURITY_POLICY_REJECTED", "거부", false, false));

    assertThat(
            registry.get("ai.chat.stream.error").tag("reason", "security_policy").counter().count())
        .isEqualTo(1);
  }
}

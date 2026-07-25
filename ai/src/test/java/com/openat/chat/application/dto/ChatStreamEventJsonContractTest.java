package com.openat.chat.application.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.chat.application.dto.ChatStreamEvent.ChatStage;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("관리자 챗봇 SSE JSON 계약")
class ChatStreamEventJsonContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("스트리밍 이벤트는 화면에 필요한 최소 필드만 직렬화한다")
  void events_serializeMinimalContract() {
    UUID requestId = UUID.randomUUID();

    JsonNode status =
        objectMapper.valueToTree(ChatStreamEvent.status(requestId, ChatStage.ANALYZING));
    JsonNode delta = objectMapper.valueToTree(ChatStreamEvent.delta(requestId, "답변"));
    JsonNode done = objectMapper.valueToTree(ChatStreamEvent.done(requestId));

    assertThat(status.get("name").asText()).isEqualTo("status");
    assertThat(status.at("/data/requestId").asText()).isEqualTo(requestId.toString());
    assertThat(status.at("/data/stage").asText()).isEqualTo("ANALYZING");
    assertThat(status.at("/data/route").isMissingNode()).isTrue();
    assertThat(delta.at("/data/text").asText()).isEqualTo("답변");
    assertThat(done.at("/data/requestId").asText()).isEqualTo(requestId.toString());
  }

  @Test
  @DisplayName("오류는 재시도와 부분 응답 여부를 명시한다")
  void error_containsRecoveryContract() {
    UUID requestId = UUID.randomUUID();

    JsonNode error =
        objectMapper.valueToTree(
            ChatStreamEvent.error(requestId, "CHAT_TIMEOUT", "시간이 오래 걸렸어.", true, true));

    assertThat(error.get("name").asText()).isEqualTo("error");
    assertThat(error.at("/data/code").asText()).isEqualTo("CHAT_TIMEOUT");
    assertThat(error.at("/data/retryable").asBoolean()).isTrue();
    assertThat(error.at("/data/partial").asBoolean()).isTrue();
  }
}

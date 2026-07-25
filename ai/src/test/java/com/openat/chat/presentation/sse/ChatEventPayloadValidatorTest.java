package com.openat.chat.presentation.sse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.chat.application.dto.ChatStreamEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatEventPayloadValidatorTest {

  private static final UUID REQUEST_ID = UUID.fromString("018f22ce-7b5a-7cc8-98c1-37a7262d2c80");
  private final ChatEventPayloadValidator validator =
      new ChatEventPayloadValidator(JsonMapper.builder().findAndAddModules().build());

  @Test
  @DisplayName("직렬화된 SSE payload가 byte 상한 안이면 허용한다")
  void validate_boundedPayload_accepts() {
    assertThatCode(() -> validator.validate(ChatStreamEvent.delta(REQUEST_ID, "검증된 문서 답변")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("직렬화된 SSE payload가 byte 상한을 넘으면 전송 전에 거부한다")
  void validate_oversizedPayload_rejects() {
    ChatStreamEvent event =
        ChatStreamEvent.delta(
            REQUEST_ID, "가".repeat(ChatEventPayloadValidator.MAX_EVENT_PAYLOAD_BYTES));

    assertThatThrownBy(() -> validator.validate(event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("크기");
  }
}

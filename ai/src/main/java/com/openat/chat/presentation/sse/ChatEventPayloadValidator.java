package com.openat.chat.presentation.sse;

import com.openat.chat.application.dto.ChatStreamEvent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class ChatEventPayloadValidator {

  static final int MAX_EVENT_PAYLOAD_BYTES = 128 * 1024;

  private final ObjectMapper objectMapper;

  ChatEventPayloadValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  void validate(ChatStreamEvent event) {
    final int payloadBytes;
    try {
      payloadBytes = objectMapper.writeValueAsBytes(event.data()).length;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("SSE 이벤트를 안전하게 직렬화할 수 없어요.", exception);
    }
    if (payloadBytes > MAX_EVENT_PAYLOAD_BYTES) {
      throw new IllegalStateException("SSE 이벤트 크기가 허용 범위를 넘었어요.");
    }
  }
}

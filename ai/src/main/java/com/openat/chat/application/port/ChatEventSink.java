package com.openat.chat.application.port;

import com.openat.chat.application.dto.ChatStreamEvent;
import java.util.function.Function;

public interface ChatEventSink {

  void emit(ChatStreamEvent event);

  boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory);

  default boolean terminate(ChatStreamEvent event) {
    return terminate(ignored -> event);
  }

  boolean isClosed();
}

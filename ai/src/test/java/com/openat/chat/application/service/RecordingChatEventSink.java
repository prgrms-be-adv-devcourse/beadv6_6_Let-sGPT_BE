package com.openat.chat.application.service;

import com.openat.chat.application.dto.ChatStreamEvent;
import com.openat.chat.application.dto.ChatStreamEvent.DeltaPayload;
import com.openat.chat.application.port.ChatEventSink;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class RecordingChatEventSink implements ChatEventSink {

  private final List<ChatStreamEvent> events = new ArrayList<>();
  private boolean partial;
  private boolean closed;

  @Override
  public void emit(ChatStreamEvent event) {
    events.add(event);
    if ("delta".equals(event.name())) {
      partial = true;
    }
  }

  @Override
  public boolean terminate(Function<Boolean, ChatStreamEvent> eventFactory) {
    if (closed) {
      return false;
    }
    events.add(eventFactory.apply(partial));
    closed = true;
    return true;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  List<String> eventNames() {
    return events.stream().map(ChatStreamEvent::name).toList();
  }

  List<ChatStreamEvent> events() {
    return List.copyOf(events);
  }

  List<DeltaPayload> deltas() {
    return events.stream()
        .filter(event -> "delta".equals(event.name()))
        .map(event -> (DeltaPayload) event.data())
        .toList();
  }

  <T> T payload(int index, Class<T> payloadType) {
    return payloadType.cast(events.get(index).data());
  }
}

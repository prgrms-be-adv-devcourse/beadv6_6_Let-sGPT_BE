package com.openat.chat.presentation.sse;

import com.openat.chat.application.port.ChatEventSink;

interface ChatStreamSink extends ChatEventSink {

  void heartbeat();

  void close();
}

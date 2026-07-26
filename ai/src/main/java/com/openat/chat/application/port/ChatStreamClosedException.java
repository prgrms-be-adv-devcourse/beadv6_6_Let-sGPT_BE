package com.openat.chat.application.port;

public class ChatStreamClosedException extends RuntimeException {

  public ChatStreamClosedException(Throwable cause) {
    super(cause);
  }
}

package com.openat.chat.application.exception;

import java.util.Objects;

public class AdminChatExecutionException extends RuntimeException {

  private final Reason reason;

  public AdminChatExecutionException(Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public AdminChatExecutionException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    SELECTION_FAILED,
    INPUT_BUDGET_EXCEEDED,
    TIMEOUT,
    BUSY,
    CANCELLED
  }
}

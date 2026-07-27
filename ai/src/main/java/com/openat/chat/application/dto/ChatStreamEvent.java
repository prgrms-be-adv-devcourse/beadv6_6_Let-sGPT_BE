package com.openat.chat.application.dto;

import java.util.UUID;

public record ChatStreamEvent(String name, Object data) {

  public static ChatStreamEvent started(UUID requestId) {
    return new ChatStreamEvent("started", new StartedPayload(requestId));
  }

  public static ChatStreamEvent status(UUID requestId, ChatStage stage) {
    return new ChatStreamEvent("status", new StatusPayload(requestId, stage));
  }

  public static ChatStreamEvent delta(UUID requestId, String text) {
    return new ChatStreamEvent("delta", new DeltaPayload(requestId, text));
  }

  public static ChatStreamEvent done(UUID requestId) {
    return new ChatStreamEvent("done", new DonePayload(requestId));
  }

  public static ChatStreamEvent error(
      UUID requestId, String code, String message, boolean retryable, boolean partial) {
    return new ChatStreamEvent(
        "error", new ErrorPayload(requestId, code, message, retryable, partial));
  }

  public enum ChatStage {
    ANALYZING,
    CALLING_TOOL,
    GENERATING
  }

  public record StartedPayload(UUID requestId) {}

  public record StatusPayload(UUID requestId, ChatStage stage) {}

  public record DeltaPayload(UUID requestId, String text) {}

  public record DonePayload(UUID requestId) {}

  public record ErrorPayload(
      UUID requestId, String code, String message, boolean retryable, boolean partial) {}
}

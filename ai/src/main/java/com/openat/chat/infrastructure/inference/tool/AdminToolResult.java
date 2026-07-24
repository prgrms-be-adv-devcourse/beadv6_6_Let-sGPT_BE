package com.openat.chat.infrastructure.inference.tool;

public record AdminToolResult(Status status, String code, String message, Object data) {

  public static AdminToolResult success(Object data) {
    return new AdminToolResult(Status.SUCCESS, "OK", "", data);
  }

  public static AdminToolResult partial(String code, String message, Object data) {
    return new AdminToolResult(Status.PARTIAL, code, message, data);
  }

  public static AdminToolResult failed(String code, String message) {
    return new AdminToolResult(Status.FAILED, code, message, null);
  }

  public enum Status {
    SUCCESS,
    PARTIAL,
    FAILED
  }
}

package com.openat.chat.application.port;

public class WeatherToolException extends RuntimeException {

  private final String code;
  private final boolean retryable;

  public WeatherToolException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public WeatherToolException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}

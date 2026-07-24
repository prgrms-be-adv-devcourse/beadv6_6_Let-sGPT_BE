package com.openat.chat.application.dto;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

public record ChatRequestDeadline(Instant expiresAt, Clock clock) {

  public ChatRequestDeadline {
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(clock, "clock");
  }

  public Duration remaining() throws TimeoutException {
    Duration remaining = Duration.between(clock.instant(), expiresAt);
    if (remaining.isNegative() || remaining.isZero()) {
      throw new TimeoutException("관리자 챗봇 요청 기한이 지났어요.");
    }
    return remaining;
  }

  public Duration boundedBy(Duration maximum) throws TimeoutException {
    Objects.requireNonNull(maximum, "maximum");
    Duration remaining = remaining();
    return remaining.compareTo(maximum) < 0 ? remaining : maximum;
  }
}

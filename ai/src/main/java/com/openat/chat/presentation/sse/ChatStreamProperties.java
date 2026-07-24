package com.openat.chat.presentation.sse;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.stream")
public class ChatStreamProperties {

  private Duration deadline = Duration.ofSeconds(170);
  private Duration emitterTimeout = Duration.ofMinutes(3);
  private Duration heartbeatInterval = Duration.ofSeconds(15);

  public Duration getDeadline() {
    return deadline;
  }

  public void setDeadline(Duration deadline) {
    this.deadline = deadline;
  }

  public Duration getEmitterTimeout() {
    return emitterTimeout;
  }

  public void setEmitterTimeout(Duration emitterTimeout) {
    this.emitterTimeout = emitterTimeout;
  }

  public Duration getHeartbeatInterval() {
    return heartbeatInterval;
  }

  public void setHeartbeatInterval(Duration heartbeatInterval) {
    this.heartbeatInterval = heartbeatInterval;
  }

  @PostConstruct
  void validate() {
    requirePositive(deadline, "chat.stream.deadline");
    requirePositive(emitterTimeout, "chat.stream.emitter-timeout");
    requirePositive(heartbeatInterval, "chat.stream.heartbeat-interval");
    if (emitterTimeout.compareTo(deadline) <= 0) {
      throw new IllegalStateException(
          "chat.stream.emitter-timeout은 chat.stream.deadline보다 길어야 해요.");
    }
  }

  private void requirePositive(Duration duration, String propertyName) {
    if (duration == null || duration.toMillis() < 1) {
      throw new IllegalStateException(propertyName + "은 1ms 이상이어야 해요.");
    }
  }
}

package com.openat.chat.infrastructure.weather;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chat.weather")
public class OpenMeteoProperties {

  private boolean enabled = true;
  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration readTimeout = Duration.ofSeconds(5);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(Duration readTimeout) {
    this.readTimeout = readTimeout;
  }

  @PostConstruct
  void validate() {
    requirePositive(connectTimeout, "chat.weather.connect-timeout");
    requirePositive(readTimeout, "chat.weather.read-timeout");
  }

  private void requirePositive(Duration duration, String property) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalStateException(property + "은 1ms 이상이어야 해요.");
    }
  }
}

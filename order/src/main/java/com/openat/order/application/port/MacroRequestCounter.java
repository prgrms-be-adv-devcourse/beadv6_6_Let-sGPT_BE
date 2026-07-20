package com.openat.order.application.port;

import java.time.Duration;
import java.util.UUID;

public interface MacroRequestCounter {
  long increment(UUID memberId, Duration window);
}

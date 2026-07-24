package com.openat.chat.application.port;

public interface DataQueryCapabilityState {

  Availability availability();

  default boolean isAvailable() {
    return availability() == Availability.AVAILABLE;
  }

  enum Availability {
    NOT_CHECKED,
    AVAILABLE,
    UNAVAILABLE
  }
}

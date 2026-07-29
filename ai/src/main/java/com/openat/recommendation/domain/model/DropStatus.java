package com.openat.recommendation.domain.model;

/** product enum에 컴파일 의존하지 않으려 값만 복제한다. 모르는 값은 {@link #UNKNOWN}이 받는다. */
public enum DropStatus {
  REGISTERED,
  OPEN,
  CLOSE,
  SOLD_OUT,
  UNKNOWN;

  public static DropStatus from(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    for (DropStatus status : values()) {
      if (status != UNKNOWN && status.name().equalsIgnoreCase(raw.trim())) {
        return status;
      }
    }
    return UNKNOWN;
  }
}

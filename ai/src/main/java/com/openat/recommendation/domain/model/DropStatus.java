package com.openat.recommendation.domain.model;

/**
 * product의 드롭 상태를 추천 도메인이 자체적으로 표현한 값. product 모듈 enum에 컴파일
 * 의존하지 않기 위해 값만 복제하고, 모르는 값은 {@link #UNKNOWN}으로 받아 "열림"으로 오판하지
 * 않는다.
 */
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

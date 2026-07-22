package com.openat.chat.domain.query;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum OrderStatus {
  PAYMENT_PENDING,
  COMPLETED,
  FAILED,
  CANCELLED,
  CANCEL_REQUESTED,
  REFUND_PENDING,
  REFUNDED,
  REFUND_FAILED;

  private static final Set<String> NAMES =
      Arrays.stream(values()).map(OrderStatus::name).collect(Collectors.toUnmodifiableSet());

  public static boolean supports(String value) {
    return value != null && NAMES.contains(value);
  }

  public static int count() {
    return NAMES.size();
  }
}

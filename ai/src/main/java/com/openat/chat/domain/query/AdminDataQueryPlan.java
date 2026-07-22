package com.openat.chat.domain.query;

import java.util.Objects;
import java.util.regex.Pattern;

public final class AdminDataQueryPlan {

  public static final int MAX_ORDER_EVENT_ROWS = 100;

  private static final int MAX_ORDER_NUMBER_LENGTH = 30;
  private static final Pattern PUBLIC_ORDER_NUMBER =
      Pattern.compile("ORD-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");

  private AdminDataQueryPlan() {}

  public record OrderLookup(
      String publicOrderNumber,
      boolean includeSnapshot,
      boolean includeProcessEvents,
      boolean includeCurrentSaga) {

    public OrderLookup {
      Objects.requireNonNull(publicOrderNumber, "publicOrderNumber");
      if (publicOrderNumber.length() > MAX_ORDER_NUMBER_LENGTH
          || !PUBLIC_ORDER_NUMBER.matcher(publicOrderNumber).matches()) {
        throw new IllegalArgumentException("공개 주문번호 형식이 올바르지 않아요.");
      }
      if (!includeSnapshot && !includeProcessEvents && !includeCurrentSaga) {
        throw new IllegalArgumentException("개별 주문 조회 범위가 하나 이상 필요해요.");
      }
    }
  }
}

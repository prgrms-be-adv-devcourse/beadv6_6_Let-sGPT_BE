package com.openat.order.application.port;

import com.openat.order.domain.model.OrderFailCode;

/** failCode를 상위 타입과 같게 받는다 — OrderFailCode는 DB CHECK 제약에 묶여 값을 늘릴 수 없다. */
public class DropNotFoundException extends ProductPortException {

  public DropNotFoundException(OrderFailCode failCode, String message, Throwable cause) {
    super(failCode, message, cause);
  }
}

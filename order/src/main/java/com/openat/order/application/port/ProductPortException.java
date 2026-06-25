package com.openat.order.application.port;

import com.openat.order.domain.model.OrderFailCode;

public class ProductPortException extends RuntimeException {

    private final OrderFailCode failCode;

    public ProductPortException(OrderFailCode failCode, String message, Throwable cause) {
        super(message, cause);
        this.failCode = failCode;
    }

    public OrderFailCode getFailCode() {
        return failCode;
    }
}

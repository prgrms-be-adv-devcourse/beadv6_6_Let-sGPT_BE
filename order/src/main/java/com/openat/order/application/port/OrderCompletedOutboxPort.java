package com.openat.order.application.port;

import com.openat.order.domain.model.Order;

public interface OrderCompletedOutboxPort {

    void save(Order order);
}

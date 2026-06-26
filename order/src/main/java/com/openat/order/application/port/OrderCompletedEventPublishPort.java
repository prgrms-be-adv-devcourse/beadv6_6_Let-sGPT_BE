package com.openat.order.application.port;

import com.openat.order.domain.model.Order;

public interface OrderCompletedEventPublishPort {

    void publish(Order order);
}

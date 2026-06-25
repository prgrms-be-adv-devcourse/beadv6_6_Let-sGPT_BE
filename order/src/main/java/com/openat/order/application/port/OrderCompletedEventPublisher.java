package com.openat.order.application.port;

import com.openat.order.domain.model.Order;

public interface OrderCompletedEventPublisher {

    void publish(Order order);
}

package com.openat.order.application.service;

import com.openat.order.domain.model.Order;

record PendingOrderCreation(Order order, boolean created) {

    static PendingOrderCreation created(Order order) {
        return new PendingOrderCreation(order, true);
    }

    static PendingOrderCreation replayed(Order order) {
        return new PendingOrderCreation(order, false);
    }
}

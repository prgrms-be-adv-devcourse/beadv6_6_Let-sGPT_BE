package com.openat.order.application.service;

import com.openat.order.domain.model.Order;

record PendingOrderCreation(Order order, boolean created) {
}

package com.openat.order.domain.repository;

import com.openat.order.domain.model.OrderHistory;

public interface OrderHistoryRepository {

    OrderHistory save(OrderHistory orderHistory);
}

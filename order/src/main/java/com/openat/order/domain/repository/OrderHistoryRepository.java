package com.openat.order.domain.repository;

import com.openat.order.domain.model.OrderHistory;
import java.util.UUID;

public interface OrderHistoryRepository {

  OrderHistory save(OrderHistory orderHistory);

  long countByOrderIdAndReasonCode(UUID orderId, String reasonCode);
}

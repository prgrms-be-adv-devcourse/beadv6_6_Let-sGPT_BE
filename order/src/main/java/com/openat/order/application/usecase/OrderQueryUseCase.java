package com.openat.order.application.usecase;

import com.openat.order.application.dto.OrderInfo;
import com.openat.order.application.dto.OrderValidationInfo;
import com.openat.order.domain.model.OrderStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderQueryUseCase {

    OrderInfo get(UUID memberId, UUID orderId);

    Page<OrderInfo> getMyOrders(UUID memberId, OrderStatus status, Pageable pageable);

    OrderValidationInfo validateForPayment(UUID orderId);
}

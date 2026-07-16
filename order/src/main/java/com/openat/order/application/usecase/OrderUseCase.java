package com.openat.order.application.usecase;

import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.OrderDetailInfo;
import com.openat.order.application.dto.OrderSummaryInfo;
import com.openat.order.application.dto.PaymentValidationInfo;
import com.openat.order.application.dto.PurchaseSignalInfo;
import com.openat.order.domain.model.OrderStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderUseCase {
    CreateOrderResult createOrder(UUID memberId, CreateOrderCommand command);

    OrderDetailInfo getMyOrder(UUID memberId, UUID orderId);

    Page<OrderSummaryInfo> getMyOrders(UUID memberId, OrderStatus status, Pageable pageable);

    OrderCancelInfo cancelOrder(UUID memberId, UUID orderId);

    PaymentValidationInfo getPaymentValidationInfo(UUID memberId, UUID orderId);

    List<PurchaseSignalInfo> getPurchaseSignals(UUID memberId, int limit);
}

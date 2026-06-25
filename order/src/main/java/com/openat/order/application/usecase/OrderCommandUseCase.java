package com.openat.order.application.usecase;

import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.OrderInfo;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import java.util.UUID;

public interface OrderCommandUseCase {

    OrderInfo create(CreateOrderCommand command);

    OrderInfo cancel(UUID memberId, UUID orderId);

    void completePayment(PaymentCompletedCommand command);

    void failPayment(PaymentFailedCommand command);

    void completeRefund(RefundCompletedCommand command);

    void failRefund(RefundFailedCommand command);
}

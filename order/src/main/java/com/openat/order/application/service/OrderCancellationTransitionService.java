package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderCancellationTransitionService {

  private final OrderRepository orderRepository;
  private final OrderHistoryRecorder orderHistoryRecorder;
  private final OrderSagaRecorder orderSagaRecorder;

  @Transactional
  public OrderCancelInfo cancelPaymentPending(UUID memberId, UUID orderId, String reasonMessage) {
    Order order = getOwnedOrder(memberId, orderId);
    OrderStatus previousStatus = order.getStatus();
    if (!order.cancelPending(Instant.now())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }
    orderHistoryRecorder.record(
        order, previousStatus, "ORDER_CANCELLED", reasonMessage, "cancel-" + orderId);
    orderSagaRecorder.recordCompensating(orderId);
    return OrderCancelInfo.from(order);
  }

  @Transactional
  public OrderCancelInfo requestRefund(
      UUID memberId, UUID orderId, String source, boolean markUnconfirmed) {
    Order order = getOwnedOrder(memberId, orderId);
    OrderStatus previousStatus = order.getStatus();
    if (!order.requestRefund(Instant.now())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }
    orderHistoryRecorder.record(
        order, previousStatus, "ORDER_CANCEL_REQUESTED", "취소 요청 등록", source + orderId);
    if (markUnconfirmed) {
      order.recordFailure(OrderFailCode.REFUND_REQUEST_FAILED, "환불 요청 접수 미확인");
    }
    orderSagaRecorder.recordCompensating(orderId);
    return OrderCancelInfo.from(order);
  }

  @Transactional
  public void clearRefundRequestFailure(UUID orderId) {
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
    if (order.getFailCode() == OrderFailCode.REFUND_REQUEST_FAILED) {
      order.clearFailure();
    }
  }

  private Order getOwnedOrder(UUID memberId, UUID orderId) {
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
    if (!order.isOwnedBy(memberId)) {
      throw new BusinessException(OrderErrorCode.NOT_OWNER);
    }
    return order;
  }
}

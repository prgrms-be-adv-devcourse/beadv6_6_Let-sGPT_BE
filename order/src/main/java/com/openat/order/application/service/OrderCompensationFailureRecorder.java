package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCompensationFailureRecorder {

  private final OrderRepository orderRepository;
  private final OrderHistoryRecorder orderHistoryRecorder;
  private final OrderSagaRecorder orderSagaRecorder;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordStockRollbackFailure(UUID orderId, String message) {
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
    if (order.getFailCode() == null) {
      order.recordFailure(OrderFailCode.STOCK_ROLLBACK_FAILED, message);
    }
    orderHistoryRecorder.record(
        order,
        order.getStatus(),
        "STOCK_ROLLBACK_FAILED",
        message,
        "stock-rollback-failed-" + orderId);
    orderSagaRecorder.recordCompensating(orderId);
    log.error(
        "Order compensation requires manual intervention. orderId={}, sagaId={}, failCode={}",
        orderId,
        order.getSagaId(),
        OrderFailCode.STOCK_ROLLBACK_FAILED);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordRefundRequestFailure(UUID orderId, String message) {
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
    order.recordFailure(OrderFailCode.REFUND_REQUEST_FAILED, message);
    orderHistoryRecorder.record(
        order,
        order.getStatus(),
        "REFUND_REQUEST_FAILED",
        message,
        "refund-request-failed-" + orderId);
    log.error(
        "Order refund compensation requires manual intervention. orderId={}, sagaId={}, failCode={}",
        orderId,
        order.getSagaId(),
        OrderFailCode.REFUND_REQUEST_FAILED);
  }
}

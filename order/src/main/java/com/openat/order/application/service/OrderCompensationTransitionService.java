package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.event.RefundStockRestoreRequested;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderSagaStep;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
import com.openat.order.domain.repository.OrderRepository;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderCompensationTransitionService {

  private static final String REFUND_RETRY_REASON = "REFUND_RETRY_TRIGGERED";

  private final OrderRepository orderRepository;
  private final OrderSagaStateRepository orderSagaStateRepository;
  private final OrderHistoryRepository orderHistoryRepository;
  private final OrderHistoryRecorder orderHistoryRecorder;
  private final OrderSagaRecorder orderSagaRecorder;
  private final ApplicationEventPublisher applicationEventPublisher;

  @Transactional
  public int prepareRefundRetry(UUID orderId) {
    Order order = getOrder(orderId);
    boolean retryable =
        order.getStatus() == OrderStatus.REFUND_FAILED
            || (order.getStatus() == OrderStatus.CANCEL_REQUESTED
                && order.getFailCode() == OrderFailCode.REFUND_REQUEST_FAILED);
    if (!retryable) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }
    int round =
        Math.toIntExact(
            orderHistoryRepository.countByOrderIdAndReasonCode(orderId, REFUND_RETRY_REASON) + 1);
    orderHistoryRecorder.record(
        order,
        order.getStatus(),
        REFUND_RETRY_REASON,
        "운영자 환불 재트리거 " + round + "회차",
        "refund-retry-" + orderId + "-r" + round);
    orderSagaRecorder.recordCompensating(orderId);
    return round;
  }

  @Transactional
  public OrderCancelInfo confirmRefund(UUID orderId) {
    Order order = getOrder(orderId);
    OrderStatus previousStatus = order.getStatus();
    if (!order.confirmRefund(Instant.now())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }
    orderHistoryRecorder.record(
        order,
        previousStatus,
        "MANUAL_CORRECTION",
        "운영자 환불 수동 확정",
        "manual-refund-confirmation-" + orderId);
    if (previousStatus == OrderStatus.REFUND_FAILED
        && !orderSagaRecorder.isCompensationCompleted(orderId)) {
      orderSagaRecorder.recordCompensating(orderId);
      applicationEventPublisher.publishEvent(
          new RefundStockRestoreRequested(
              order.getId(), order.getDropId(), order.getMemberId(), order.getQuantity()));
    }
    return OrderCancelInfo.from(order);
  }

  @Transactional(readOnly = true)
  public Order stockRollbackRetryTarget(UUID orderId) {
    Order order = getOrder(orderId);
    boolean compensating =
        orderSagaStateRepository
            .findByOrderId(orderId)
            .map(state -> state.getCurrentStep() == OrderSagaStep.COMPENSATING)
            .orElse(false);
    if (!compensating || !isStockRollbackState(order.getStatus())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }
    return order;
  }

  @Transactional
  public void completeStockRollbackRetry(UUID orderId) {
    Order order = getOrder(orderId);
    boolean compensating =
        orderSagaStateRepository
            .findByOrderId(orderId)
            .map(state -> state.getCurrentStep() == OrderSagaStep.COMPENSATING)
            .orElse(false);
    if (!compensating || !isStockRollbackState(order.getStatus())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }
    if (order.getFailCode() == OrderFailCode.STOCK_ROLLBACK_FAILED) {
      order.clearFailure();
    }
    orderHistoryRecorder.record(
        order,
        order.getStatus(),
        "STOCK_ROLLBACK_RETRY_COMPLETED",
        "재고 롤백 재처리 성공",
        "stock-rollback-retry-completed-" + orderId);
    orderSagaRecorder.recordCompensationCompleted(orderId);
  }

  @Transactional(readOnly = true)
  public OrderCancelInfo getInfo(UUID orderId) {
    return OrderCancelInfo.from(getOrder(orderId));
  }

  private Order getOrder(UUID orderId) {
    return orderRepository
        .findById(orderId)
        .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
  }

  private boolean isStockRollbackState(OrderStatus status) {
    return status == OrderStatus.CANCELLED
        || status == OrderStatus.FAILED
        || status == OrderStatus.REFUNDED;
  }
}

package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.event.RefundStockRestoreRequested;
import com.openat.order.application.event.StockAdjustment;
import com.openat.order.application.event.StockAdjustmentReason;
import com.openat.order.application.port.OrderCompletedOutboxPort;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventService {

  private static final String EVENT_IDEMPOTENCY_DELIMITER = "-";
  private static final int SOURCE_EVENT_KEY_MAX_LENGTH = 100;

  private final OrderRepository orderRepository;
  private final OrderHistoryRecorder orderHistoryRecorder;
  private final OrderCompletedOutboxPort orderCompletedOutboxPort;
  private final OrderSagaRecorder orderSagaRecorder;
  private final ApplicationEventPublisher applicationEventPublisher;

  @Transactional
  public void handlePaymentCompleted(PaymentCompletedCommand command) {
    Order order = getOrder(command.orderId());

    completePayment(order, command.paymentId(), command.amount(), "결제 성공 이벤트 처리");
  }

  @Transactional
  public void reconcilePaymentCompleted(UUID orderId, UUID paymentId, long amount) {
    Order order = getOrder(orderId);

    completePayment(order, paymentId, amount, "결제 상태 조회로 완료 보정");
  }

  private void completePayment(Order order, UUID paymentId, long amount, String reasonMessage) {

    if (order.getStatus() == OrderStatus.COMPLETED) {
      return;
    }

    requireStatus(order, "결제 성공", OrderStatus.PAYMENT_PENDING);

    validateAmount(order, amount);

    OrderStatus before = order.getStatus();
    if (!order.complete(paymentId, Instant.now())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }

    orderHistoryRecorder.record(
        order,
        before,
        "ORDER_COMPLETED",
        reasonMessage,
        eventSourceKey("payment-complete", order.getId(), paymentId));
    orderSagaRecorder.recordCompleted(order.getId());
    orderCompletedOutboxPort.save(order);
    publishStockAdjustment(order, StockAdjustmentReason.COMPLETED);
  }

  @Transactional
  public void handlePaymentFailed(PaymentFailedCommand command) {
    Order order = getOrder(command.orderId());

    if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
      return;
    }

    OrderStatus before = order.getStatus();

    orderHistoryRecorder.record(
        order,
        before,
        "PAYMENT_ATTEMPT_FAILED",
        command.reason(),
        eventSourceKey("payment-failed", command.orderId(), command.paymentId()));
  }

  @Transactional
  public void handleRefundCompleted(RefundCompletedCommand command) {
    Order order = getOrder(command.orderId());

    if (order.getStatus() == OrderStatus.REFUNDED) {
      return;
    }

    requireStatus(
        order,
        "환불 완료",
        OrderStatus.CANCEL_REQUESTED,
        OrderStatus.REFUND_PENDING,
        OrderStatus.REFUND_FAILED,
        OrderStatus.CANCELLED,
        OrderStatus.COMPLETED);

    validateRefundAmountLimit(order, command.amount());

    if (order.getStatus() == OrderStatus.COMPLETED
        && command.amount() < order.getTotalPrice()) {
      orderHistoryRecorder.record(
          order,
          order.getStatus(),
          "PARTIAL_REFUND_COMPLETED",
          refundAmountMessage("직행 부분환불 처리", command.amount(), order.getTotalPrice()),
          eventSourceKey("refund-completed", command.orderId(), command.refundId()));
      return;
    }

    OrderStatus before = order.getStatus();
    if (!order.refund(Instant.now())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }

    orderHistoryRecorder.record(
        order,
        before,
        "ORDER_REFUNDED",
        command.amount() < order.getTotalPrice()
            ? refundAmountMessage("잔액 환불로 완결", command.amount(), order.getTotalPrice())
            : "환불 완료 이벤트 처리",
        eventSourceKey("refund-completed", command.orderId(), command.refundId()));
    boolean compensationCompleted = orderSagaRecorder.isCompensationCompleted(order.getId());
    if (!compensationCompleted) {
      orderSagaRecorder.recordCompensating(order.getId());
      applicationEventPublisher.publishEvent(
          new RefundStockRestoreRequested(
              order.getId(), order.getDropId(), order.getMemberId(), order.getQuantity()));
    }
    if (order.getCompletedAt() != null) {
      publishStockAdjustment(order, StockAdjustmentReason.REFUNDED);
    }
  }

  @Transactional
  public void handleRefundFailed(RefundFailedCommand command) {
    Order order = getOrder(command.orderId());

    if (order.getStatus() == OrderStatus.REFUND_FAILED) {
      return;
    }

    requireStatus(order, "환불 실패", OrderStatus.CANCEL_REQUESTED, OrderStatus.REFUND_PENDING);

    OrderStatus before = order.getStatus();
    if (!order.failRefund(command.reason())) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }

    orderHistoryRecorder.record(
        order,
        before,
        "REFUND_FAILED",
        command.reason(),
        eventSourceKey("refund-failed", command.orderId(), command.refundId()));
    log.error("Order refund failed. orderId={}, reason={}", command.orderId(), command.reason());
  }

  private void validateAmount(Order order, long eventAmount) {
    if (order.getTotalPrice() != eventAmount) {
      throw new BusinessException(
          OrderErrorCode.INVALID_INPUT,
          "주문 금액과 이벤트 금액이 일치하지 않습니다: orderId=%s, orderAmount=%d, eventAmount=%d"
              .formatted(order.getId(), order.getTotalPrice(), eventAmount));
    }
  }

  private void validateRefundAmountLimit(Order order, long eventAmount) {
    if (eventAmount > order.getTotalPrice()) {
      throw new BusinessException(
          OrderErrorCode.INVALID_INPUT,
          "환불 이벤트 금액이 주문 금액을 초과합니다: orderId=%s, orderAmount=%d, eventAmount=%d"
              .formatted(order.getId(), order.getTotalPrice(), eventAmount));
    }
  }

  private String refundAmountMessage(String prefix, long eventAmount, long orderAmount) {
    return "%s (이벤트 금액 %d / 주문 금액 %d)".formatted(prefix, eventAmount, orderAmount);
  }

  private void requireStatus(Order order, String eventName, OrderStatus... allowedStatuses) {
    for (OrderStatus allowedStatus : allowedStatuses) {
      if (order.getStatus() == allowedStatus) {
        return;
      }
    }
    throw new BusinessException(
        OrderErrorCode.INVALID_STATUS,
        "%s 이벤트 처리 가능한 상태가 아닙니다: orderId=%s, status=%s"
            .formatted(eventName, order.getId(), order.getStatus()));
  }

  private String eventSourceKey(String type, UUID orderId, UUID correlationId) {
    String key =
        type + EVENT_IDEMPOTENCY_DELIMITER + (correlationId != null ? correlationId : orderId);
    if (key.length() <= SOURCE_EVENT_KEY_MAX_LENGTH) {
      return key;
    }
    return key.substring(0, SOURCE_EVENT_KEY_MAX_LENGTH);
  }

  private Order getOrder(UUID orderId) {
    return orderRepository
        .findById(orderId)
        .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
  }

  private void publishStockAdjustment(Order order, StockAdjustmentReason reason) {
    applicationEventPublisher.publishEvent(
        new StockAdjustment(UUID.randomUUID(), order.getDropId(), order.getQuantity(), reason));
  }
}

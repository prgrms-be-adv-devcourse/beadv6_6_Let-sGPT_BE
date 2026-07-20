package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.StockRollbackTarget;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentExpiryTransitionService {

  private static final int MAX_CONSECUTIVE_LOOKUP_FAILURES = 3;

  private final OrderRepository orderRepository;
  private final OrderHistoryRecorder orderHistoryRecorder;
  private final OrderSagaRecorder orderSagaRecorder;

  @Transactional
  public Optional<StockRollbackTarget> expire(UUID orderId) {
    return failPendingOrder(
        orderId, OrderFailCode.PAYMENT_EXPIRED, "결제 대기 시간이 만료되었습니다.", "PAYMENT_EXPIRED");
  }

  @Transactional
  public Optional<StockRollbackTarget> expireAlreadySettled(UUID orderId, String message) {
    return failPendingOrder(orderId, OrderFailCode.PAYMENT_ALREADY_REFUNDED, message, "PAYMENT_ALREADY_REFUNDED");
  }

  @Transactional
  public Optional<StockRollbackTarget> recordLookupFailure(UUID orderId, Instant failedAt) {
    Order order = getOrder(orderId);
    if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
      return Optional.empty();
    }
    int failures = order.recordPaymentStatusCheckFailure(failedAt);
    if (failures < MAX_CONSECUTIVE_LOOKUP_FAILURES) {
      return Optional.empty();
    }
    Optional<StockRollbackTarget> target =
        failOrder(
            order,
            OrderFailCode.PAYMENT_NO_RESPONSE,
            "결제 상태 조회가 연속 3회 실패했습니다.",
            "PAYMENT_NO_RESPONSE");
    target.ifPresent(
        ignored ->
            log.error(
                "Payment status could not be determined. orderId={}, consecutiveFailures={}",
                orderId,
                failures));
    return target;
  }

  @Transactional
  public void resetLookupFailures(UUID orderId) {
    Order order = getOrder(orderId);
    if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
      order.resetPaymentStatusCheckFailures();
    }
  }

  @Transactional
  public void deferPendingLookup(UUID orderId, Instant nextCheckAt) {
    Order order = getOrder(orderId);
    if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
      return;
    }
    order.deferPaymentStatusCheck(nextCheckAt);
    if (order.getPaymentExpiresAt().plusSeconds(30 * 60L).isBefore(Instant.now())) {
      log.error(
          "Payment remains pending after expiry. orderId={}, paymentExpiresAt={}, nextCheckAt={}",
          orderId,
          order.getPaymentExpiresAt(),
          nextCheckAt);
    }
  }

  @Transactional
  public long deferPaymentMismatch(UUID orderId, Instant nextCheckAt) {
    Order order = getOrder(orderId);
    if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
      order.deferPaymentStatusCheck(nextCheckAt);
    }
    return order.getTotalPrice();
  }

  private Optional<StockRollbackTarget> failPendingOrder(
      UUID orderId, OrderFailCode failCode, String message, String reasonCode) {
    Order order = getOrder(orderId);
    if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
      return Optional.empty();
    }
    return failOrder(order, failCode, message, reasonCode);
  }

  private Optional<StockRollbackTarget> failOrder(
      Order order, OrderFailCode failCode, String message, String reasonCode) {
    OrderStatus previousStatus = order.getStatus();
    if (!order.fail(failCode, message, Instant.now())) {
      return Optional.empty();
    }
    orderHistoryRecorder.record(
        order,
        previousStatus,
        reasonCode,
        message,
        reasonCode.toLowerCase().replace('_', '-') + "-" + order.getId());
    orderSagaRecorder.recordCompensating(order.getId());
    return Optional.of(
        new StockRollbackTarget(
            order.getId(), order.getDropId(), order.getMemberId(), order.getQuantity()));
  }

  private Order getOrder(UUID orderId) {
    return orderRepository
        .findById(orderId)
        .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
  }
}

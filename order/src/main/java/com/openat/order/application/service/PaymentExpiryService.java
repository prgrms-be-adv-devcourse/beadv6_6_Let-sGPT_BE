package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentStatus;
import com.openat.order.application.dto.PaymentStatusInfo;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.dto.StockRollbackTarget;
import com.openat.order.application.port.PaymentStatusPort;
import com.openat.order.application.port.PaymentStatusPortException;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentExpiryService {

  private static final Duration PENDING_RECHECK_DELAY = Duration.ofMinutes(5);

  private final PaymentStatusPort paymentStatusPort;
  private final PaymentExpiryTransitionService transitionService;
  private final OrderEventService orderEventService;
  private final ProductIntegrationPort productIntegrationPort;
  private final OrderSagaRecorder orderSagaRecorder;
  private final OrderCompensationFailureRecorder compensationFailureRecorder;

  public void process(UUID orderId) {
    PaymentStatusInfo payment;
    try {
      payment = paymentStatusPort.findByOrderId(orderId);
    } catch (PaymentStatusPortException exception) {
      transitionService.recordLookupFailure(orderId, Instant.now()).ifPresent(this::restoreStock);
      return;
    }

    if (payment.status() == PaymentStatus.APPROVED) {
      if (payment.amount() == null) {
        log.warn("Approved payment reconciliation skipped without amount. orderId={}", orderId);
        transitionService.deferPendingLookup(orderId, Instant.now().plus(PENDING_RECHECK_DELAY));
        return;
      }
      try {
        orderEventService.reconcilePaymentCompleted(orderId, payment.paymentId(), payment.amount());
      } catch (BusinessException exception) {
        if (exception.getErrorCode() != OrderErrorCode.INVALID_INPUT) {
          throw exception;
        }
        long orderAmount =
            transitionService.deferPaymentMismatch(
                orderId, Instant.now().plus(PENDING_RECHECK_DELAY));
        log.error(
            "Approved payment amount mismatch. orderId={}, orderAmount={}, paymentAmount={}",
            orderId,
            orderAmount,
            payment.amount(),
            exception);
      }
      return;
    }
    if (payment.status() == PaymentStatus.REFUNDED) {
      transitionService
          .expireAlreadySettled(orderId, "결제 후 전액 환불 처리된 주문입니다.")
          .ifPresent(this::restoreStock);
      return;
    }
    if (payment.status() == PaymentStatus.PARTIALLY_REFUNDED) {
      transitionService
          .expireAlreadySettled(orderId, "결제 후 일부 환불 처리된 주문입니다.")
          .ifPresent(this::restoreStock);
      return;
    }
    if (payment.status() == PaymentStatus.FAILED
        || payment.status() == PaymentStatus.CANCELED
        || payment.status() == PaymentStatus.NO_PAYMENT) {
      transitionService.expire(orderId).ifPresent(this::restoreStock);
      return;
    }
    transitionService.deferPendingLookup(orderId, Instant.now().plus(PENDING_RECHECK_DELAY));
  }

  private void restoreStock(StockRollbackTarget target) {
    try {
      productIntegrationPort.restoreStock(
          target.dropId(),
          new StockRestoreCommand(target.orderId(), target.memberId(), target.quantity()));
      orderSagaRecorder.recordCompensationCompleted(target.orderId());
    } catch (ProductPortException exception) {
      compensationFailureRecorder.recordStockRollbackFailure(
          target.orderId(), exception.getMessage());
    }
  }
}

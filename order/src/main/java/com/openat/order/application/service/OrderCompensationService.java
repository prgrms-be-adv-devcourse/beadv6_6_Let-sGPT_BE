package com.openat.order.application.service;

import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.PaymentRefundResult;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.PaymentRefundPort;
import com.openat.order.application.port.PaymentRefundPortException;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.Order;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderCompensationService {

  private final OrderCompensationTransitionService transitionService;
  private final PaymentRefundPort paymentRefundPort;
  private final ProductIntegrationPort productIntegrationPort;
  private final OrderCompensationFailureRecorder failureRecorder;

  public OrderCancelInfo retryRefund(UUID orderId) {
    int round = transitionService.prepareRefundRetry(orderId);
    String idempotencyKey = OrderCancellationService.automaticRefundKey(orderId) + "-r" + round;
    try {
      PaymentRefundResult result = paymentRefundPort.requestRefund(orderId, idempotencyKey);
      if (result != PaymentRefundResult.REFUND_ACCEPTED) {
        failureRecorder.recordRefundRequestFailure(
            orderId, "Refund retry was not accepted by payment: round=" + round);
      }
    } catch (PaymentRefundPortException exception) {
      failureRecorder.recordRefundRequestFailure(orderId, exception.getMessage());
    }
    return transitionService.getInfo(orderId);
  }

  public OrderCancelInfo confirmRefund(UUID orderId) {
    return transitionService.confirmRefund(orderId);
  }

  public OrderCancelInfo retryStockRollback(UUID orderId) {
    Order order = transitionService.stockRollbackRetryTarget(orderId);
    try {
      productIntegrationPort.restoreStock(
          order.getDropId(),
          new StockRestoreCommand(order.getId(), order.getMemberId(), order.getQuantity()));
    } catch (ProductPortException exception) {
      failureRecorder.recordStockRollbackFailure(orderId, exception.getMessage());
      return OrderCancelInfo.from(order);
    }
    transitionService.completeStockRollbackRetry(orderId);
    return transitionService.getInfo(orderId);
  }
}

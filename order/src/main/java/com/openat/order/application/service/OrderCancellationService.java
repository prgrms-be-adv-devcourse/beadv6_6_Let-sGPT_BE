package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.PaymentRefundResult;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.PaymentPendingException;
import com.openat.order.application.port.PaymentRefundPort;
import com.openat.order.application.port.PaymentRefundPortException;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderCancellationService {

  private static final String AUTOMATIC_REFUND_KEY_PREFIX = "refund-order-";

  private final OrderRepository orderRepository;
  private final OrderCancellationTransitionService transitionService;
  private final PaymentRefundPort paymentRefundPort;
  private final ProductIntegrationPort productIntegrationPort;
  private final OrderSagaRecorder orderSagaRecorder;
  private final OrderCompensationFailureRecorder compensationFailureRecorder;

  public OrderCancelInfo cancel(UUID memberId, UUID orderId) {
    Order order = getOwnedOrder(memberId, orderId);
    if (order.getStatus() == OrderStatus.COMPLETED) {
      throw new BusinessException(OrderErrorCode.ALREADY_COMPLETED);
    }
    if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }

    PaymentRefundResult result;
    try {
      result = paymentRefundPort.requestRefund(orderId, automaticRefundKey(orderId));
    } catch (PaymentPendingException exception) {
      throw new BusinessException(OrderErrorCode.PAYMENT_IN_PROGRESS);
    } catch (PaymentRefundPortException exception) {
      return cancelAndRestoreStock(memberId, order, "환불 API 무응답으로 결제 전 낙관 확정");
    }

    if (result == PaymentRefundResult.REFUND_ACCEPTED) {
      return transitionService.requestRefund(memberId, orderId, "cancel-refund-accepted-", false);
    }
    return cancelAndRestoreStock(memberId, order, "결제 전 주문 취소");
  }

  public OrderCancelInfo requestRefund(UUID memberId, UUID orderId) {
    Order order = getOwnedOrder(memberId, orderId);
    if (order.getStatus() != OrderStatus.COMPLETED) {
      throw new BusinessException(OrderErrorCode.INVALID_STATUS);
    }

    OrderCancelInfo result =
        transitionService.requestRefund(memberId, orderId, "refund-request-", true);
    try {
      PaymentRefundResult refundResult =
          paymentRefundPort.requestRefund(orderId, automaticRefundKey(orderId));
      if (refundResult == PaymentRefundResult.REFUND_ACCEPTED) {
        transitionService.clearRefundRequestFailure(orderId);
      } else {
        compensationFailureRecorder.recordRefundRequestFailure(
            orderId, "Completed order was not accepted by payment refund API");
      }
    } catch (PaymentRefundPortException exception) {
      compensationFailureRecorder.recordRefundRequestFailure(orderId, exception.getMessage());
    }
    return result;
  }

  private OrderCancelInfo cancelAndRestoreStock(UUID memberId, Order order, String reasonMessage) {
    OrderCancelInfo result =
        transitionService.cancelPaymentPending(memberId, order.getId(), reasonMessage);
    try {
      productIntegrationPort.restoreStock(
          order.getDropId(),
          new StockRestoreCommand(order.getId(), order.getMemberId(), order.getQuantity()));
      orderSagaRecorder.recordCompensationCompleted(order.getId());
    } catch (ProductPortException exception) {
      compensationFailureRecorder.recordStockRollbackFailure(order.getId(), exception.getMessage());
    }
    return result;
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

  static String automaticRefundKey(UUID orderId) {
    return AUTOMATIC_REFUND_KEY_PREFIX + orderId;
  }
}

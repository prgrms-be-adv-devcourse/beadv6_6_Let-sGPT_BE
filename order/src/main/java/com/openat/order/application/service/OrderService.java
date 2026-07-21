package com.openat.order.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.OrderDetailInfo;
import com.openat.order.application.dto.OrderSummaryInfo;
import com.openat.order.application.dto.PaymentValidationInfo;
import com.openat.order.application.dto.PurchaseSignalInfo;
import com.openat.order.application.usecase.OrderUseCase;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

  private final OrderRepository orderRepository;
  private final OrderCreationService orderCreationService;
  private final OrderCancellationService orderCancellationService;
  private final OrderCompensationService orderCompensationService;

  @Override
  public CreateOrderResult createOrder(UUID memberId, CreateOrderCommand command) {
    return orderCreationService.create(memberId, command);
  }

  @Override
  @Transactional(readOnly = true)
  public OrderDetailInfo getMyOrder(UUID memberId, UUID orderId) {
    Order order = getOwnedOrder(memberId, orderId);
    return OrderDetailInfo.from(order);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<OrderSummaryInfo> getMyOrders(UUID memberId, OrderStatus status, Pageable pageable) {
    return orderRepository.findByMemberId(memberId, status, pageable).map(OrderSummaryInfo::from);
  }

  @Override
  public OrderCancelInfo cancelOrder(UUID memberId, UUID orderId) {
    return orderCancellationService.cancel(memberId, orderId);
  }

  @Override
  public OrderCancelInfo requestRefund(UUID memberId, UUID orderId) {
    return orderCancellationService.requestRefund(memberId, orderId);
  }

  @Override
  public OrderCancelInfo retryRefund(UUID orderId) {
    return orderCompensationService.retryRefund(orderId);
  }

  @Override
  public OrderCancelInfo confirmRefund(UUID orderId) {
    return orderCompensationService.confirmRefund(orderId);
  }

  @Override
  public OrderCancelInfo retryStockRollback(UUID orderId) {
    return orderCompensationService.retryStockRollback(orderId);
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentValidationInfo getPaymentValidationInfo(UUID memberId, UUID orderId) {
    Order order = getOwnedOrder(memberId, orderId);
    orderCreationService.rejectUnstockedOrderForPaymentValidation(order);
    return PaymentValidationInfo.from(order);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PurchaseSignalInfo> getPurchaseSignals(UUID memberId, int limit) {
    return orderRepository
        .findPurchaseSignals(memberId, OrderStatus.COMPLETED, PageRequest.of(0, limit))
        .stream()
        .map(PurchaseSignalInfo::from)
        .toList();
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

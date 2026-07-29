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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService implements OrderUseCase {

  // 목록 정렬은 서버가 고정한다. createdAt이 같은 주문끼리 순서가 흔들리면 LIMIT/OFFSET
  // 페이지 경계에서 같은 주문이 중복되거나 누락되므로, PK이자 시간순 UUID인 id를
  // 타이브레이커로 붙여 전순서를 만든다.
  private static final Sort MY_ORDERS_SORT = Sort.by(Sort.Direction.DESC, "createdAt", "id");

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
    return orderRepository
        .findByMemberId(memberId, status, fixSort(pageable))
        .map(OrderSummaryInfo::from);
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
  public PaymentValidationInfo getPaymentValidationInfo(UUID orderId) {
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new BusinessException(OrderErrorCode.NOT_FOUND));
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

  /** 클라이언트가 보낸 sort는 무시한다 — 부분 정렬 키만 오면 다시 페이지 경계가 깨진다. */
  private static Pageable fixSort(Pageable pageable) {
    if (pageable.isUnpaged()) {
      return Pageable.unpaged(MY_ORDERS_SORT);
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), MY_ORDERS_SORT);
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

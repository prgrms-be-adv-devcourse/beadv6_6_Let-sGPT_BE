package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.PaymentRefundResult;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.PaymentRefundPort;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCompensationServiceTest {

  @Mock OrderCompensationTransitionService transitionService;
  @Mock PaymentRefundPort paymentRefundPort;
  @Mock ProductIntegrationPort productIntegrationPort;
  @Mock OrderCompensationFailureRecorder failureRecorder;
  @InjectMocks OrderCompensationService service;

  @Test
  void should_use_round_idempotency_key_when_refund_retried() {
    UUID orderId = UUID.randomUUID();
    when(transitionService.prepareRefundRetry(orderId)).thenReturn(3);
    when(paymentRefundPort.requestRefund(orderId, "refund-order-" + orderId + "-r3"))
        .thenReturn(PaymentRefundResult.REFUND_ACCEPTED);
    when(transitionService.getInfo(orderId))
        .thenReturn(new OrderCancelInfo(orderId, OrderStatus.CANCEL_REQUESTED));

    OrderCancelInfo result = service.retryRefund(orderId);

    assertThat(result.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    verify(paymentRefundPort).requestRefund(orderId, "refund-order-" + orderId + "-r3");
  }

  @Test
  void should_complete_saga_after_stock_rollback_retry_succeeds() {
    Order order = order();
    when(transitionService.stockRollbackRetryTarget(order.getId())).thenReturn(order);
    when(transitionService.getInfo(order.getId()))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCELLED));

    OrderCancelInfo result = service.retryStockRollback(order.getId());

    assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    ArgumentCaptor<StockRestoreCommand> command =
        ArgumentCaptor.forClass(StockRestoreCommand.class);
    verify(productIntegrationPort).restoreStock(any(), command.capture());
    assertThat(command.getValue().orderId()).isEqualTo(order.getId());
    verify(transitionService).completeStockRollbackRetry(order.getId());
  }

  private Order order() {
    Order order =
        Order.create()
            .orderNumber("ORD-1")
            .memberId(UUID.randomUUID())
            .dropId(UUID.randomUUID())
            .productId(UUID.randomUUID())
            .sellerId(UUID.randomUUID())
            .productName("상품")
            .quantity(1)
            .unitPrice(10_000L)
            .idempotencyKey("idem")
            .now(Instant.now())
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    return order;
  }
}

package com.openat.order.application.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentStatus;
import com.openat.order.application.dto.PaymentStatusInfo;
import com.openat.order.application.dto.StockRollbackTarget;
import com.openat.order.application.port.PaymentStatusPort;
import com.openat.order.application.port.PaymentStatusPortException;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.domain.exception.OrderErrorCode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentExpiryServiceTest {

  @Mock PaymentStatusPort paymentStatusPort;
  @Mock PaymentExpiryTransitionService transitionService;
  @Mock OrderEventService orderEventService;
  @Mock ProductIntegrationPort productIntegrationPort;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @Mock OrderCompensationFailureRecorder compensationFailureRecorder;
  @InjectMocks PaymentExpiryService service;

  @Test
  void should_reconcile_completed_when_payment_approved() {
    UUID orderId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    when(paymentStatusPort.findByOrderId(orderId))
        .thenReturn(new PaymentStatusInfo(paymentId, PaymentStatus.APPROVED, 10_000L));

    service.process(orderId);

    verify(orderEventService).reconcilePaymentCompleted(orderId, paymentId, 10_000L);
    verify(productIntegrationPort, never()).restoreStock(any(), any());
  }

  @Test
  void should_defer_without_closing_when_approved_amount_mismatches() {
    UUID orderId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    when(paymentStatusPort.findByOrderId(orderId))
        .thenReturn(new PaymentStatusInfo(paymentId, PaymentStatus.APPROVED, 9_000L));
    org.mockito.Mockito.doThrow(new BusinessException(OrderErrorCode.INVALID_INPUT))
        .when(orderEventService)
        .reconcilePaymentCompleted(orderId, paymentId, 9_000L);
    when(transitionService.deferPaymentMismatch(org.mockito.ArgumentMatchers.eq(orderId), any()))
        .thenReturn(10_000L);

    service.process(orderId);

    verify(transitionService).deferPaymentMismatch(org.mockito.ArgumentMatchers.eq(orderId), any());
    verify(transitionService, never()).expire(orderId);
    verify(productIntegrationPort, never()).restoreStock(any(), any());
  }

  @Test
  void should_skip_approved_reconciliation_when_amount_is_missing() {
    UUID orderId = UUID.randomUUID();
    when(paymentStatusPort.findByOrderId(orderId))
        .thenReturn(new PaymentStatusInfo(UUID.randomUUID(), PaymentStatus.APPROVED, null));

    service.process(orderId);

    verify(orderEventService, never()).reconcilePaymentCompleted(any(), any(), anyLong());
    verify(transitionService).deferPendingLookup(org.mockito.ArgumentMatchers.eq(orderId), any());
  }

  @Test
  void should_expire_and_restore_stock_when_payment_failed() {
    UUID orderId = UUID.randomUUID();
    StockRollbackTarget target = target(orderId);
    when(paymentStatusPort.findByOrderId(orderId))
        .thenReturn(new PaymentStatusInfo(UUID.randomUUID(), PaymentStatus.FAILED, null));
    when(transitionService.expire(orderId)).thenReturn(Optional.of(target));

    service.process(orderId);

    verify(productIntegrationPort).restoreStock(any(), any());
    verify(orderSagaRecorder).recordCompensationCompleted(orderId);
  }

  @Test
  void should_skip_when_first_payment_lookup_failure_occurs() {
    UUID orderId = UUID.randomUUID();
    when(paymentStatusPort.findByOrderId(orderId))
        .thenThrow(new PaymentStatusPortException("timeout", new RuntimeException()));
    when(transitionService.recordLookupFailure(any(), any())).thenReturn(Optional.empty());

    service.process(orderId);

    verify(productIntegrationPort, never()).restoreStock(any(), any());
    verify(orderEventService, never()).reconcilePaymentCompleted(any(), any(), anyLong());
  }

  @Test
  void should_restore_stock_when_third_consecutive_lookup_failure_closes_order() {
    UUID orderId = UUID.randomUUID();
    when(paymentStatusPort.findByOrderId(orderId))
        .thenThrow(new PaymentStatusPortException("timeout", new RuntimeException()));
    when(transitionService.recordLookupFailure(any(), any()))
        .thenReturn(Optional.of(target(orderId)));

    service.process(orderId);

    verify(productIntegrationPort).restoreStock(any(), any());
    verify(orderSagaRecorder).recordCompensationCompleted(orderId);
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"REFUNDED", "PARTIALLY_REFUNDED"})
  void should_expire_and_restore_stock_when_payment_is_already_refunded(PaymentStatus status) {
    UUID orderId = UUID.randomUUID();
    StockRollbackTarget target = target(orderId);
    when(paymentStatusPort.findByOrderId(orderId))
        .thenReturn(new PaymentStatusInfo(UUID.randomUUID(), status, null));
    when(transitionService.expire(orderId)).thenReturn(Optional.of(target));

    service.process(orderId);

    verify(transitionService).expire(orderId);
    verify(productIntegrationPort).restoreStock(any(), any());
    verify(orderSagaRecorder).recordCompensationCompleted(orderId);
    verify(transitionService, never()).resetLookupFailures(orderId);
  }

  @ParameterizedTest
  @EnumSource(
      value = PaymentStatus.class,
      names = {"PENDING", "PAYMENT_PENDING"})
  void should_still_skip_when_payment_is_pending(PaymentStatus status) {
    UUID orderId = UUID.randomUUID();
    when(paymentStatusPort.findByOrderId(orderId))
        .thenReturn(new PaymentStatusInfo(UUID.randomUUID(), status, null));

    service.process(orderId);

    verify(transitionService).deferPendingLookup(org.mockito.ArgumentMatchers.eq(orderId), any());
    verify(transitionService, never()).expire(orderId);
    verify(productIntegrationPort, never()).restoreStock(any(), any());
  }

  private StockRollbackTarget target(UUID orderId) {
    return new StockRollbackTarget(orderId, UUID.randomUUID(), UUID.randomUUID(), 2);
  }
}

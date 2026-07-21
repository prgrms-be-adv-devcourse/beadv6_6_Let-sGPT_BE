package com.openat.order.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.event.RefundStockRestoreRequested;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.application.service.OrderCompensationFailureRecorder;
import com.openat.order.application.service.OrderSagaRecorder;
import com.openat.order.domain.model.OrderFailCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class RefundStockRestoreListenerTest {

  @Mock ProductIntegrationPort productIntegrationPort;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @Mock OrderCompensationFailureRecorder compensationFailureRecorder;
  @InjectMocks RefundStockRestoreListener listener;

  @Test
  void should_complete_saga_after_refunded_stock_is_restored() {
    RefundStockRestoreRequested event = event();

    listener.restore(event);

    ArgumentCaptor<StockRestoreCommand> command =
        ArgumentCaptor.forClass(StockRestoreCommand.class);
    verify(productIntegrationPort)
        .restoreStock(org.mockito.Mockito.eq(event.dropId()), command.capture());
    assertThat(command.getValue().orderId()).isEqualTo(event.orderId());
    assertThat(command.getValue().buyerId()).isEqualTo(event.memberId());
    assertThat(command.getValue().quantity()).isEqualTo(event.quantity());
    verify(orderSagaRecorder).recordCompensationCompleted(event.orderId());
  }

  @Test
  void should_record_compensation_failure_without_reverting_refunded_order() {
    RefundStockRestoreRequested event = event();
    doThrow(new ProductPortException(OrderFailCode.STOCK_ROLLBACK_FAILED, "timeout"))
        .when(productIntegrationPort)
        .restoreStock(any(), any());

    listener.restore(event);

    verify(compensationFailureRecorder).recordStockRollbackFailure(event.orderId(), "timeout");
    verify(orderSagaRecorder, never()).recordCompensationCompleted(any());
  }

  @Test
  void should_restore_stock_only_after_refund_transaction_commits() throws NoSuchMethodException {
    TransactionalEventListener annotation =
        AnnotationUtils.findAnnotation(
            RefundStockRestoreListener.class.getMethod(
                "restore", RefundStockRestoreRequested.class),
            TransactionalEventListener.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
  }

  private RefundStockRestoreRequested event() {
    return new RefundStockRestoreRequested(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
  }
}

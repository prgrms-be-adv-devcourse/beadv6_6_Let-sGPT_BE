package com.openat.order.infrastructure.event;

import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.event.RefundStockRestoreRequested;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.application.service.OrderCompensationFailureRecorder;
import com.openat.order.application.service.OrderSagaRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class RefundStockRestoreListener {

  private final ProductIntegrationPort productIntegrationPort;
  private final OrderSagaRecorder orderSagaRecorder;
  private final OrderCompensationFailureRecorder compensationFailureRecorder;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void restore(RefundStockRestoreRequested event) {
    try {
      productIntegrationPort.restoreStock(
          event.dropId(),
          new StockRestoreCommand(event.orderId(), event.memberId(), event.quantity()));
      orderSagaRecorder.recordCompensationCompleted(event.orderId());
    } catch (ProductPortException exception) {
      compensationFailureRecorder.recordStockRollbackFailure(
          event.orderId(), exception.getMessage());
    }
  }
}

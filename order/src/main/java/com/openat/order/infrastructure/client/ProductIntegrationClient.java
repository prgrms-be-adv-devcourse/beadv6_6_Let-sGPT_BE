package com.openat.order.infrastructure.client;

import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.event.StockAdjustment;
import com.openat.order.application.event.StockAdjustmentReason;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.ProductPortDtos.OperationType;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

@Component
public class ProductIntegrationClient implements ProductIntegrationPort {

  private static final long[] BACKOFF_MILLIS = {500L, 1_000L};

  private final ProductInternalApiClient productInternalApiClient;
  private final RetrySleeper retrySleeper;
  private final CircuitBreaker circuitBreaker;
  private final ApplicationEventPublisher applicationEventPublisher;

  public ProductIntegrationClient(
      ProductInternalApiClient productInternalApiClient,
      RetrySleeper retrySleeper,
      @Qualifier("productCircuitBreaker") CircuitBreaker circuitBreaker,
      ApplicationEventPublisher applicationEventPublisher) {
    this.productInternalApiClient = productInternalApiClient;
    this.retrySleeper = retrySleeper;
    this.circuitBreaker = circuitBreaker;
    this.applicationEventPublisher = applicationEventPublisher;
  }

  @Override
  public OrderSnapshotInfo fetchOrderSnapshot(UUID dropId) {
    ProductPortException lastFailure = null;
    for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
      try {
        OrderSnapshotResponse response =
            circuitBreaker.executeSupplier(
                () -> {
                  try {
                    return productInternalApiClient.fetchOrderSnapshot(dropId);
                  } catch (RestClientException exception) {
                    throw toProductPortException(OperationType.FETCH_ORDER_SNAPSHOT, exception);
                  }
                });
        return new OrderSnapshotInfo(
            response.productId(),
            response.sellerId(),
            response.unitPrice(),
            response.productName());
      } catch (CallNotPermittedException exception) {
        throw new ProductPortException(
            OrderFailCode.PRODUCT_INTEGRATION_FAILED, "Product circuit breaker is open", exception);
      } catch (ProductPortException exception) {
        lastFailure = exception;
        if (isBusinessFailure(exception) || attempt == BACKOFF_MILLIS.length) {
          throw exception;
        }
        sleep(BACKOFF_MILLIS[attempt], OperationType.FETCH_ORDER_SNAPSHOT, exception);
      }
    }
    throw lastFailure;
  }

  @Override
  public void decreaseStock(UUID dropId, StockDecreaseCommand command) {
    executeStockChange(
        OperationType.DECREASE_STOCK,
        () ->
            productInternalApiClient.decreaseStock(
                dropId,
                new StockChangeRequest(command.orderId(), command.buyerId(), command.quantity())));
    publishStockAdjustment(dropId, command.quantity(), StockAdjustmentReason.CREATED);
  }

  @Override
  public void restoreStock(UUID dropId, StockRestoreCommand command) {
    executeStockChangeOnce(
        OperationType.RESTORE_STOCK,
        () ->
            productInternalApiClient.restoreStock(
                dropId,
                new StockChangeRequest(command.orderId(), command.buyerId(), command.quantity())));
    publishStockAdjustment(dropId, command.quantity(), StockAdjustmentReason.CANCELLED);
  }

  private void executeStockChangeOnce(OperationType operationType, Runnable operation) {
    try {
      circuitBreaker.executeRunnable(
          () -> {
            try {
              operation.run();
            } catch (RestClientException exception) {
              throw toProductPortException(operationType, exception);
            }
          });
    } catch (CallNotPermittedException exception) {
      throw new ProductPortException(
          fallbackFailCode(operationType), "Product circuit breaker is open", exception);
    }
  }

  private void publishStockAdjustment(UUID dropId, int count, StockAdjustmentReason reason) {
    applicationEventPublisher.publishEvent(
        new StockAdjustment(UUID.randomUUID(), dropId, count, reason));
  }

  private void executeStockChange(OperationType operationType, Runnable operation) {
    ProductPortException lastFailure = null;
    for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
      try {
        circuitBreaker.executeRunnable(
            () -> {
              try {
                operation.run();
              } catch (RestClientException exception) {
                throw toProductPortException(operationType, exception);
              }
            });
        return;
      } catch (CallNotPermittedException exception) {
        throw new ProductPortException(
            fallbackFailCode(operationType), "Product circuit breaker is open", exception);
      } catch (ProductPortException exception) {
        lastFailure = exception;
        if (isBusinessFailure(exception) || attempt == BACKOFF_MILLIS.length) {
          throw exception;
        }
        sleep(BACKOFF_MILLIS[attempt], operationType, exception);
      }
    }
    throw lastFailure;
  }

  private void sleep(long milliseconds, OperationType operationType, ProductPortException failure) {
    try {
      retrySleeper.sleep(milliseconds);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new ProductPortException(
          fallbackFailCode(operationType), "Product retry interrupted", failure);
    }
  }

  private boolean isBusinessFailure(ProductPortException exception) {
    return exception.getFailCode() == OrderFailCode.SOLD_OUT
        || exception.getFailCode() == OrderFailCode.DROP_NOT_OPEN
        || exception.getFailCode() == OrderFailCode.DROP_CLOSED
        || exception.getFailCode() == OrderFailCode.LIMIT_EXCEEDED;
  }

  private ProductPortException toProductPortException(
      OperationType operationType, RestClientException exception) {
    if (exception instanceof ProductApiException productApiException) {
      ProductErrorResponse errorResponse = productApiException.getErrorResponse();
      String message =
          errorResponse.message() != null ? errorResponse.message() : exception.getMessage();
      return new ProductPortException(
          toOrderFailCode(operationType, errorResponse.failCode()), message, exception);
    }
    return new ProductPortException(
        fallbackFailCode(operationType), exception.getMessage(), exception);
  }

  private OrderFailCode toOrderFailCode(OperationType operationType, String failCode) {
    if (failCode == null) {
      return fallbackFailCode(operationType);
    }
    return switch (failCode) {
      case "DROP_SOLD_OUT", "SOLD_OUT" -> OrderFailCode.SOLD_OUT;
      case "DROP_NOT_OPEN", "NOT_OPEN" -> OrderFailCode.DROP_NOT_OPEN;
      case "DROP_CLOSED", "CLOSED" -> OrderFailCode.DROP_CLOSED;
      case "DROP_LIMIT_EXCEEDED", "LIMIT_EXCEEDED" -> OrderFailCode.LIMIT_EXCEEDED;
      default -> fallbackFailCode(operationType);
    };
  }

  private OrderFailCode fallbackFailCode(OperationType operationType) {
    return operationType == OperationType.RESTORE_STOCK
        ? OrderFailCode.STOCK_ROLLBACK_FAILED
        : OrderFailCode.PRODUCT_INTEGRATION_FAILED;
  }
}

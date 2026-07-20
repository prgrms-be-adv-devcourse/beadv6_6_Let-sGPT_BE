package com.openat.order.infrastructure.client;

import com.openat.order.application.dto.PaymentRefundResult;
import com.openat.order.application.port.PaymentPendingException;
import com.openat.order.application.port.PaymentRefundPort;
import com.openat.order.application.port.PaymentRefundPortException;
import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@Component
public class PaymentRefundIntegrationClient implements PaymentRefundPort {

  private static final long[] BACKOFF_MILLIS = {500L, 1_000L};

  private final PaymentRefundInternalApiClient paymentRefundInternalApiClient;
  private final RetrySleeper retrySleeper;
  private final CircuitBreaker circuitBreaker;

  public PaymentRefundIntegrationClient(
      PaymentRefundInternalApiClient paymentRefundInternalApiClient,
      RetrySleeper retrySleeper,
      @Qualifier("paymentCircuitBreaker") CircuitBreaker circuitBreaker) {
    this.paymentRefundInternalApiClient = paymentRefundInternalApiClient;
    this.retrySleeper = retrySleeper;
    this.circuitBreaker = circuitBreaker;
  }

  @Override
  public PaymentRefundResult requestRefund(UUID orderId, String idempotencyKey) {
    RestClientException lastFailure = null;
    for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
      try {
        return map(
            circuitBreaker.executeSupplier(
                () -> paymentRefundInternalApiClient.requestRefund(orderId, idempotencyKey)));
      } catch (CallNotPermittedException exception) {
        throw new PaymentRefundPortException(
            "Payment circuit breaker is open: orderId=" + orderId, exception);
      } catch (RestClientException exception) {
        lastFailure = exception;
        if (!isRetryable(exception) || attempt == BACKOFF_MILLIS.length) {
          break;
        }
        sleep(BACKOFF_MILLIS[attempt], orderId, exception);
      }
    }
    if (lastFailure instanceof PaymentRefundApiException apiException && apiException.isConflict()) {
      throw new PaymentPendingException(
          "Payment refund still pending after retries: orderId=" + orderId, lastFailure);
    }
    throw new PaymentRefundPortException(
        "Payment refund request failed after retries: orderId=" + orderId, lastFailure);
  }

  private PaymentRefundResult map(RefundResponse response) {
    return switch (response.result()) {
      case NO_PAYMENT -> PaymentRefundResult.PAYMENT_NOT_COMPLETED;
      case REFUND_ACCEPTED -> PaymentRefundResult.REFUND_ACCEPTED;
    };
  }

  private boolean isRetryable(RestClientException exception) {
    if (exception instanceof PaymentRefundApiException apiException) {
      return apiException.isServerError() || apiException.isConflict();
    }
    if (!(exception instanceof ResourceAccessException)) {
      return false;
    }
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof HttpTimeoutException
          || cause instanceof HttpConnectTimeoutException
          || cause instanceof ConnectException) {
        return true;
      }
      cause = cause.getCause();
    }
    return true;
  }

  private void sleep(long milliseconds, UUID orderId, RestClientException failure) {
    try {
      retrySleeper.sleep(milliseconds);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new PaymentRefundPortException(
          "Payment refund retry interrupted: orderId=" + orderId, failure);
    }
  }
}

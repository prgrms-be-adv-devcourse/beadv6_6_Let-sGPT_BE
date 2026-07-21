package com.openat.order.infrastructure.client;

import com.openat.order.application.dto.PaymentStatus;
import com.openat.order.application.dto.PaymentStatusInfo;
import com.openat.order.application.port.PaymentStatusPort;
import com.openat.order.application.port.PaymentStatusPortException;
import com.openat.order.infrastructure.client.PaymentStatusPortDtos.PaymentStatusResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

@Component
public class PaymentStatusIntegrationClient implements PaymentStatusPort {

  private static final long[] BACKOFF_MILLIS = {500L, 1_000L};

  private final PaymentStatusInternalApiClient internalApiClient;
  private final RetrySleeper retrySleeper;
  private final CircuitBreaker circuitBreaker;

  public PaymentStatusIntegrationClient(
      PaymentStatusInternalApiClient internalApiClient,
      RetrySleeper retrySleeper,
      @Qualifier("paymentCircuitBreaker") CircuitBreaker circuitBreaker) {
    this.internalApiClient = internalApiClient;
    this.retrySleeper = retrySleeper;
    this.circuitBreaker = circuitBreaker;
  }

  @Override
  public PaymentStatusInfo findByOrderId(UUID orderId) {
    RestClientException lastFailure = null;
    for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
      try {
        return map(circuitBreaker.executeSupplier(() -> internalApiClient.findByOrderId(orderId)));
      } catch (CallNotPermittedException exception) {
        throw new PaymentStatusPortException(
            "Payment circuit breaker is open: orderId=" + orderId, exception);
      } catch (PaymentStatusNotFoundException exception) {
        return new PaymentStatusInfo(null, PaymentStatus.NO_PAYMENT, null);
      } catch (RestClientException exception) {
        lastFailure = exception;
        if (!isRetryable(exception) || attempt == BACKOFF_MILLIS.length) {
          break;
        }
        sleep(BACKOFF_MILLIS[attempt], orderId, exception);
      }
    }
    throw new PaymentStatusPortException(
        "Payment status lookup failed after retries: orderId=" + orderId, lastFailure);
  }

  private PaymentStatusInfo map(PaymentStatusResponse response) {
    if (response.status() == PaymentStatusPortDtos.PaymentState.APPROVED
        && response.paymentId() == null) {
      throw new RestClientException("Approved payment response has no paymentId");
    }
    return new PaymentStatusInfo(
        response.paymentId(), PaymentStatus.valueOf(response.status().name()), response.amount());
  }

  private boolean isRetryable(RestClientException exception) {
    if (exception instanceof PaymentStatusApiException apiException) {
      return apiException.isServerError();
    }
    return exception instanceof ResourceAccessException;
  }

  private void sleep(long milliseconds, UUID orderId, RestClientException failure) {
    try {
      retrySleeper.sleep(milliseconds);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
      throw new PaymentStatusPortException(
          "Payment status retry interrupted: orderId=" + orderId, failure);
    }
  }
}

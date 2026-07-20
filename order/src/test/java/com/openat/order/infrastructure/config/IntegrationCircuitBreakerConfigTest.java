package com.openat.order.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class IntegrationCircuitBreakerConfigTest {

  private final IntegrationCircuitBreakerConfig config = new IntegrationCircuitBreakerConfig();

  @Test
  void should_open_product_circuit_after_technical_failure_threshold() {
    var registry = config.integrationCircuitBreakerRegistry();
    CircuitBreaker circuitBreaker = config.productCircuitBreaker(registry);

    for (int i = 0; i < 20; i++) {
      boolean fail = i < 11;
      catchThrowable(
          () ->
              circuitBreaker.executeRunnable(
                  () -> {
                    if (fail) {
                      throw new ResourceAccessException("timeout");
                    }
                  }));
    }

    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void should_exclude_business_failure_from_product_circuit_metrics() {
    var registry = config.integrationCircuitBreakerRegistry();
    CircuitBreaker circuitBreaker = config.productCircuitBreaker(registry);

    for (int i = 0; i < 20; i++) {
      catchThrowable(
          () ->
              circuitBreaker.executeRunnable(
                  () -> {
                    throw new ProductPortException(OrderFailCode.SOLD_OUT, "sold out");
                  }));
    }

    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void should_apply_same_policy_to_payment_circuit() {
    var registry = config.integrationCircuitBreakerRegistry();
    CircuitBreaker circuitBreaker = config.paymentCircuitBreaker(registry);

    for (int i = 0; i < 20; i++) {
      catchThrowable(
          () ->
              circuitBreaker.executeRunnable(
                  () -> {
                    throw new ResourceAccessException("timeout");
                  }));
    }

    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(
            circuitBreaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1))
        .isEqualTo(30_000L);
  }
}

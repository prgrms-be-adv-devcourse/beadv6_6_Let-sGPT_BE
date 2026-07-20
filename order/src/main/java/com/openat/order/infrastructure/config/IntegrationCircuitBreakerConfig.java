package com.openat.order.infrastructure.config;

import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.PaymentRefundApiException;
import com.openat.order.infrastructure.client.PaymentStatusApiException;
import com.openat.order.infrastructure.client.ProductApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;

@Configuration
public class IntegrationCircuitBreakerConfig {

  @Bean
  public CircuitBreakerRegistry integrationCircuitBreakerRegistry() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .minimumNumberOfCalls(20)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .recordException(this::isTechnicalFailure)
            .build();
    return CircuitBreakerRegistry.of(config);
  }

  @Bean("productCircuitBreaker")
  public CircuitBreaker productCircuitBreaker(CircuitBreakerRegistry registry) {
    return registry.circuitBreaker("product-integration");
  }

  @Bean("paymentCircuitBreaker")
  public CircuitBreaker paymentCircuitBreaker(CircuitBreakerRegistry registry) {
    return registry.circuitBreaker("payment-integration");
  }

  private boolean isTechnicalFailure(Throwable throwable) {
    if (throwable instanceof ProductPortException exception) {
      return exception.getFailCode() != OrderFailCode.SOLD_OUT
          && exception.getFailCode() != OrderFailCode.DROP_NOT_OPEN
          && exception.getFailCode() != OrderFailCode.DROP_CLOSED
          && exception.getFailCode() != OrderFailCode.LIMIT_EXCEEDED;
    }
    if (throwable instanceof ProductApiException exception) {
      return exception.isServerError();
    }
    if (throwable instanceof PaymentRefundApiException exception) {
      return exception.isServerError();
    }
    if (throwable instanceof PaymentStatusApiException exception) {
      return exception.isServerError();
    }
    return throwable instanceof ResourceAccessException;
  }
}

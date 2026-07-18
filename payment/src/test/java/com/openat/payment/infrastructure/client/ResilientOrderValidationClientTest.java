package com.openat.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

// 순수 단위테스트 — 주문검증 데코레이터는 서킷만 적용(리미터 없음). open 시 ORDER_SERVICE_UNAVAILABLE 매핑 검증.
class ResilientOrderValidationClientTest {

  private final RealOrderValidationClient delegate = mock(RealOrderValidationClient.class);
  private final UUID orderId = UUID.randomUUID();
  private final UUID memberId = UUID.randomUUID();

  // ResilienceConfig.orderCircuitBreaker의 recordExceptions를 그대로 반영(윈도우만 테스트용 5로 축소).
  private CircuitBreaker breaker() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(5)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .recordExceptions(
                IllegalStateException.class,
                org.springframework.web.client.ResourceAccessException.class,
                HttpServerErrorException.class)
            .build();
    return CircuitBreaker.of("order-test", config);
  }

  @Test
  void 연속_5회_실패하면_서킷이_open되고_6번째는_ORDER_SERVICE_UNAVAILABLE로_매핑된다() {
    when(delegate.validate(any(), any(), anyLong()))
        .thenThrow(new IllegalStateException("주문 조회 실패"));
    ResilientOrderValidationClient client =
        new ResilientOrderValidationClient(delegate, breaker());

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> client.validate(orderId, memberId, 1_000L))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> client.validate(orderId, memberId, 1_000L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE);

    verify(delegate, times(5)).validate(any(), any(), anyLong());
  }

  @Test
  void 주문서비스_5xx가_연속_5회면_서킷이_open되고_6번째는_ORDER_SERVICE_UNAVAILABLE로_매핑된다() {
    when(delegate.validate(any(), any(), anyLong()))
        .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
    ResilientOrderValidationClient client =
        new ResilientOrderValidationClient(delegate, breaker());

    // 빠른 5xx도 기록되는 실패로 취급 — 최초 5회는 원 예외가 그대로 전파된다.
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> client.validate(orderId, memberId, 1_000L))
          .isInstanceOf(HttpServerErrorException.class);
    }
    // 서킷 open 이후 6번째는 delegate를 호출하지 않고 503 도메인 예외로 폴백.
    assertThatThrownBy(() -> client.validate(orderId, memberId, 1_000L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE);

    verify(delegate, times(5)).validate(any(), any(), anyLong());
  }

  @Test
  void 주문서비스_4xx는_서킷을_트립하지_않고_예외가_그대로_전파된다() {
    when(delegate.validate(any(), any(), anyLong()))
        .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
    CircuitBreaker cb = breaker();
    ResilientOrderValidationClient client = new ResilientOrderValidationClient(delegate, cb);

    // 4xx는 recordExceptions 화이트리스트 밖 — 서킷 실패로 집계되지 않는다.
    // 6회(minimumNumberOfCalls 초과) 던져도 서킷이 open되지 않아 매번 원 예외가 전파된다.
    for (int i = 0; i < 6; i++) {
      assertThatThrownBy(() -> client.validate(orderId, memberId, 1_000L))
          .isInstanceOf(HttpClientErrorException.class);
    }

    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    verify(delegate, times(6)).validate(any(), any(), anyLong());
  }

  @Test
  void 정상_응답은_그대로_반환된다() {
    OrderValidationResult expected =
        new OrderValidationResult(memberId, 1_000L, "CONFIRMED", true);
    when(delegate.validate(orderId, memberId, 1_000L)).thenReturn(expected);
    ResilientOrderValidationClient client =
        new ResilientOrderValidationClient(delegate, breaker());

    assertThat(client.validate(orderId, memberId, 1_000L)).isEqualTo(expected);
  }
}

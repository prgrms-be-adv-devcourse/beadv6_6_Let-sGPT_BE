package com.openat.payment.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.client.TossRefundResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

// 순수 단위테스트 — 실제 resilience4j 코어를 mock delegate에 합성해 데코레이터의 폴백/합성 순서를 검증한다.
class ResilientTossPaymentClientTest {

  private final RealTossPaymentClient delegate = mock(RealTossPaymentClient.class);
  private final UUID orderId = UUID.randomUUID();

  // 실패 5회면 open되는 서킷(minimumNumberOfCalls=5, failureRate=50%).
  private CircuitBreaker breaker() {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(5)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofMinutes(1))
            .recordExceptions(IllegalStateException.class)
            .build();
    return CircuitBreaker.of("toss-test", config);
  }

  private RateLimiter permissiveLimiter() {
    return RateLimiter.of(
        "toss-rl-permissive",
        RateLimiterConfig.custom()
            .limitForPeriod(1000)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)
            .build());
  }

  private RateLimiter singlePermitLimiter() {
    return RateLimiter.of(
        "toss-rl-single",
        RateLimiterConfig.custom()
            .limitForPeriod(1)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ZERO)
            .build());
  }

  // 운영과 동일한 재시도 시맨틱(maxAttempts=2, 네트워크 오류만 재시도, 불명확 상태는 무시). 대기는 테스트 속도상 짧게.
  private Retry retry() {
    return Retry.of(
        "toss-confirm-test",
        RetryConfig.custom()
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(10))
            .retryExceptions(ResourceAccessException.class)
            .ignoreExceptions(
                CallNotPermittedException.class,
                RequestNotPermitted.class,
                BusinessException.class,
                IllegalStateException.class)
            .build());
  }

  @Test
  void confirm이_연속_5회_실패하면_서킷이_open되고_6번째는_PG_UNAVAILABLE로_매핑된다() {
    when(delegate.confirmPayment(anyString(), any(), anyLong(), anyString()))
        .thenThrow(new IllegalStateException("토스 confirm 실패: status=500"));
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, breaker(), permissiveLimiter(), retry());

    // 최초 5회는 기록되는 실패(IllegalStateException)가 그대로 전파된다.
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> client.confirmPayment("pk", orderId, 1_000L, "idem"))
          .isInstanceOf(IllegalStateException.class);
    }
    // 서킷 open 이후 6번째는 delegate를 호출하지 않고 503 도메인 예외로 폴백.
    assertThatThrownBy(() -> client.confirmPayment("pk", orderId, 1_000L, "idem"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.PG_UNAVAILABLE);

    verify(delegate, times(5)).confirmPayment(anyString(), any(), anyLong(), anyString());
  }

  @Test
  void 유량_거절이_서킷_실패로_잡히지_않고_confirm에서_PG_UNAVAILABLE로_매핑된다() {
    when(delegate.confirmPayment(anyString(), any(), anyLong(), anyString()))
        .thenReturn(TossConfirmResult.approved("tx-1"));
    CircuitBreaker cb = breaker();
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, cb, singlePermitLimiter(), retry());

    // 첫 호출은 허가를 소진하고 정상 통과.
    assertThat(client.confirmPayment("pk", orderId, 1_000L, "idem").approved()).isTrue();
    // 두 번째는 리미터 거절 → 503 매핑. 리미터가 서킷 바깥이라 서킷 실패로 기록되지 않음.
    assertThatThrownBy(() -> client.confirmPayment("pk", orderId, 1_000L, "idem"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.PG_UNAVAILABLE);

    assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    // delegate는 허가된 첫 호출에서만 실행됨.
    verify(delegate, times(1)).confirmPayment(anyString(), any(), anyLong(), anyString());
  }

  @Test
  void confirm의_첫_네트워크_오류는_재시도되어_두번째_성공이_반환된다() {
    when(delegate.confirmPayment(anyString(), any(), anyLong(), anyString()))
        .thenThrow(new ResourceAccessException("EOF reached while reading"))
        .thenReturn(TossConfirmResult.approved("tx-1"));
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, breaker(), permissiveLimiter(), retry());

    // 유휴 keep-alive 커넥션 재사용 EOF는 멱등 재시도로 흡수되어 정상 결과 반환.
    assertThat(client.confirmPayment("pk", orderId, 1_000L, "idem").approved()).isTrue();
    verify(delegate, times(2)).confirmPayment(anyString(), any(), anyLong(), anyString());
  }

  @Test
  void confirm이_재시도_후에도_네트워크_오류면_PG_UNAVAILABLE로_매핑된다() {
    when(delegate.confirmPayment(anyString(), any(), anyLong(), anyString()))
        .thenThrow(new ResourceAccessException("EOF reached while reading"));
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, breaker(), permissiveLimiter(), retry());

    // 원호출 1 + 재시도 1 = 2회 모두 네트워크 오류 → 503 도메인 예외로 변환.
    assertThatThrownBy(() -> client.confirmPayment("pk", orderId, 1_000L, "idem"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.PG_UNAVAILABLE);

    verify(delegate, times(2)).confirmPayment(anyString(), any(), anyLong(), anyString());
  }

  @Test
  void confirm의_5xx_불명_상태_예외는_재시도없이_1회만_호출되고_그대로_전파된다() {
    when(delegate.confirmPayment(anyString(), any(), anyLong(), anyString()))
        .thenThrow(new IllegalStateException("토스 confirm 실패: status=500"));
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, breaker(), permissiveLimiter(), retry());

    // 5xx 불명 상태(IllegalStateException)는 재시도 대상이 아님 — 1회만 호출되고 그대로 전파(기존 보정 로직 경로).
    assertThatThrownBy(() -> client.confirmPayment("pk", orderId, 1_000L, "idem"))
        .isInstanceOf(IllegalStateException.class);
    verify(delegate, times(1)).confirmPayment(anyString(), any(), anyLong(), anyString());
  }

  @Test
  void 서킷이_open이면_refund는_UNKNOWN으로_폴백한다() {
    when(delegate.refundPayment(anyString(), anyLong(), anyString()))
        .thenThrow(new IllegalStateException("토스 환불 실패"));
    CircuitBreaker cb = breaker();
    cb.transitionToOpenState();
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, cb, permissiveLimiter(), retry());

    TossRefundResult result = client.refundPayment("pk", 1_000L, "idem");

    assertThat(result.status()).isEqualTo(TossRefundResult.Status.UNKNOWN);
    verify(delegate, times(0)).refundPayment(anyString(), anyLong(), anyString());
  }

  @Test
  void 조회_메서드는_서킷을_거치지_않고_예외를_그대로_전파한다() {
    when(delegate.queryPaymentStatus("pk"))
        .thenThrow(new IllegalStateException("토스 결제조회 실패: status=500"));
    CircuitBreaker cb = breaker();
    cb.transitionToOpenState(); // 서킷이 open이어도 조회는 데코레이션 없이 위임되어야 함.
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, cb, singlePermitLimiter(), retry());

    assertThatThrownBy(() -> client.queryPaymentStatus("pk"))
        .isInstanceOf(IllegalStateException.class);
    verify(delegate, times(1)).queryPaymentStatus("pk");
  }

  @Test
  void 조회_성공은_그대로_반환된다() {
    TossQueryResult expected = TossQueryResult.of(TossQueryResult.Status.APPROVED, "tx-1");
    when(delegate.queryPaymentStatus("pk")).thenReturn(expected);
    ResilientTossPaymentClient client =
        new ResilientTossPaymentClient(delegate, breaker(), permissiveLimiter(), retry());

    assertThat(client.queryPaymentStatus("pk")).isEqualTo(expected);
  }
}

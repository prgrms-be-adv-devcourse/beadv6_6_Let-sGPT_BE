package com.openat.payment.infrastructure.client;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossPaymentDetail;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.client.TossRefundResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 토스 PG 아웃바운드 회복탄력성 데코레이터.
 *
 * <p>실제 클라이언트({@link RealTossPaymentClient})를 감싼다. {@code @Primary}라 포트를 주입받는 쪽은 이 데코레이터를 본다.
 *
 * <p>합성 순서: {@code RateLimiter(CircuitBreaker(real))} — 유량 거절({@link RequestNotPermitted})이 서킷 실패로 잡히면
 * 안 되므로 리미터를 서킷 바깥에 둔다.
 *
 * <p>폴백:
 * <ul>
 *   <li>confirm 계열: 서킷 open({@link CallNotPermittedException})·유량 거절 → 503 {@code PG_UNAVAILABLE}</li>
 *   <li>refund: 서킷 open·유량 거절 → {@link TossRefundResult#unknown()}(PENDING 유지, 보조 웹훅이 나중에 확정)</li>
 *   <li>조회 계열(TTL 스캐너·PG 대사·환불 웹훅): 서킷/리미터를 거치지 않고 그대로 위임 — 예외 전파로 기존 강제 FAILED/재시도 로직 유지</li>
 * </ul>
 */
@Slf4j
@Primary
@Component
public class ResilientTossPaymentClient implements TossPaymentClient {

  private final TossPaymentClient delegate;
  private final CircuitBreaker circuitBreaker;
  private final RateLimiter rateLimiter;

  public ResilientTossPaymentClient(
      RealTossPaymentClient delegate,
      @Qualifier("tossCircuitBreaker") CircuitBreaker circuitBreaker,
      @Qualifier("tossRateLimiter") RateLimiter rateLimiter) {
    this.delegate = delegate;
    this.circuitBreaker = circuitBreaker;
    this.rateLimiter = rateLimiter;
  }

  @Override
  public TossConfirmResult confirmPayment(
      String paymentKey, UUID orderId, Long amount, String idempotencyKey) {
    return callConfirm(() -> delegate.confirmPayment(paymentKey, orderId, amount, idempotencyKey));
  }

  @Override
  public TossConfirmResult confirmCharge(
      String paymentKey, UUID chargeId, Long amount, String idempotencyKey) {
    return callConfirm(() -> delegate.confirmCharge(paymentKey, chargeId, amount, idempotencyKey));
  }

  @Override
  public TossRefundResult refundPayment(String pgPaymentKey, Long amount, String idempotencyKey) {
    try {
      return decorate(() -> delegate.refundPayment(pgPaymentKey, amount, idempotencyKey)).get();
    } catch (CallNotPermittedException | RequestNotPermitted e) {
      // 서킷 open/유량 거절도 응답 불확실과 동일 취급 — PENDING 유지, 보조 웹훅이 나중에 확정.
      log.warn("[ResilientTossPaymentClient] 환불 호출 차단({}), UNKNOWN 처리: pgPaymentKey={}",
          e.getClass().getSimpleName(), pgPaymentKey);
      return TossRefundResult.unknown();
    }
  }

  // 조회 계열 — 데코레이션 없이 그대로 위임(예외 전파로 기존 스캐너·대사·웹훅 로직 유지).
  @Override
  public TossQueryResult queryPaymentStatus(String paymentKey) {
    return delegate.queryPaymentStatus(paymentKey);
  }

  @Override
  public TossPaymentDetail queryPaymentDetail(String pgPaymentKey) {
    return delegate.queryPaymentDetail(pgPaymentKey);
  }

  @Override
  public TossQueryResult queryRefundStatus(String pgPaymentKey, String pgRefundKey, Long amount) {
    return delegate.queryRefundStatus(pgPaymentKey, pgRefundKey, amount);
  }

  private TossConfirmResult callConfirm(Supplier<TossConfirmResult> call) {
    try {
      return decorate(call).get();
    } catch (CallNotPermittedException | RequestNotPermitted e) {
      log.warn("[ResilientTossPaymentClient] confirm 호출 차단: {}", e.getClass().getSimpleName());
      throw new BusinessException(PaymentErrorCode.PG_UNAVAILABLE);
    }
  }

  private <T> Supplier<T> decorate(Supplier<T> call) {
    return RateLimiter.decorateSupplier(
        rateLimiter, CircuitBreaker.decorateSupplier(circuitBreaker, call));
  }
}

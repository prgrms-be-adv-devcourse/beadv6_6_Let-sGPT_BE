package com.openat.payment.infrastructure.client;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 주문검증 내부 API 아웃바운드 회복탄력성 데코레이터.
 *
 * <p>서킷만 적용(리미터 없음) — 인바운드 요청당 1:1 호출이라 증폭이 없어 리미터 불요. 실제 클라이언트
 * ({@link RealOrderValidationClient})를 감싸며 {@code @Primary}라 포트를 주입받는 쪽은 이 데코레이터를 본다.
 *
 * <p>폴백: 서킷 open({@link CallNotPermittedException}) → 503 {@code ORDER_SERVICE_UNAVAILABLE}.
 */
@Slf4j
@Primary
@Component
public class ResilientOrderValidationClient implements OrderValidationClient {

  private final OrderValidationClient delegate;
  private final CircuitBreaker circuitBreaker;

  public ResilientOrderValidationClient(
      RealOrderValidationClient delegate,
      @Qualifier("orderCircuitBreaker") CircuitBreaker circuitBreaker) {
    this.delegate = delegate;
    this.circuitBreaker = circuitBreaker;
  }

  @Override
  public OrderValidationResult validate(UUID orderId, UUID claimedMemberId, Long claimedAmount) {
    try {
      return CircuitBreaker.decorateSupplier(
              circuitBreaker, () -> delegate.validate(orderId, claimedMemberId, claimedAmount))
          .get();
    } catch (CallNotPermittedException e) {
      log.warn("[ResilientOrderValidationClient] 주문검증 호출 차단(서킷 open): orderId={}", orderId);
      throw new BusinessException(PaymentErrorCode.ORDER_SERVICE_UNAVAILABLE);
    }
  }
}

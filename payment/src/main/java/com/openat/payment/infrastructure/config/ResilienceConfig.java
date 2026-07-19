package com.openat.payment.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 아웃바운드 회복탄력성 코어(프로그래매틱) 설정.
 *
 * <p>스타터·어노테이션 미사용(Boot 4.1 AOP/자동설정 리스크) — 데코레이터가 이 빈들을 주입받아
 * 직접 합성한다({@code ResilientTossPaymentClient}/{@code ResilientOrderValidationClient}).
 *
 * <p>튜닝값은 env 외부화 + 코드 기본값(재컴파일 없는 rollout). 윈도우 크기(10)·최소호출수(5) 같은
 * 구조 파라미터는 튜닝 대상이 아니라 코드 고정.
 */
@Slf4j
@Configuration
public class ResilienceConfig {

  // 구조 파라미터(튜닝 대상 아님) — 코드 고정.
  private static final int WINDOW_SIZE = 10;
  private static final int MIN_CALLS = 5;
  private static final float SLOW_CALL_RATE = 80f;

  @Bean
  public CircuitBreakerRegistry circuitBreakerRegistry() {
    return CircuitBreakerRegistry.ofDefaults();
  }

  @Bean
  public RateLimiterRegistry rateLimiterRegistry() {
    return RateLimiterRegistry.ofDefaults();
  }

  /**
   * 토스 confirm 계열 보호용 서킷. COUNT 윈도우 10 / 최소 5호출 / 실패율·slowCall 시간·open 대기는 env.
   * 실패 기록 대상은 5xx가 던지는 IllegalStateException과 네트워크 오류 ResourceAccessException뿐(4xx는 정상 응답).
   */
  @Bean
  public CircuitBreaker tossCircuitBreaker(
      CircuitBreakerRegistry registry,
      @Value("${toss.cb.failure-rate:30}") float failureRate,
      @Value("${toss.cb.slow-call-ms:3000}") long slowCallMs,
      @Value("${toss.cb.wait-open-s:10}") long waitOpenS) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(WINDOW_SIZE)
            .minimumNumberOfCalls(MIN_CALLS)
            .failureRateThreshold(failureRate)
            .slowCallDurationThreshold(Duration.ofMillis(slowCallMs))
            .slowCallRateThreshold(SLOW_CALL_RATE)
            .waitDurationInOpenState(Duration.ofSeconds(waitOpenS))
            .recordExceptions(IllegalStateException.class, ResourceAccessException.class)
            .build();
    CircuitBreaker circuitBreaker = registry.circuitBreaker("toss", config);
    // OPEN 진입/복귀 사후 분석용 — 상태 전이 시점을 로그로 남긴다.
    circuitBreaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                log.warn(
                    "[ResilienceConfig] toss 서킷 상태 전이: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));
    return circuitBreaker;
  }

  /**
   * 주문검증 내부 API 보호용 서킷. read 타임아웃 2s에 맞춰 slowCall 1.5s(env). 실패율·open 대기는 toss와 동일 계열로 고정.
   *
   * <p>실패 기록 대상: 네트워크 오류(ResourceAccessException)와 주문 서비스의 빠른 5xx(HttpServerErrorException).
   * RealOrderValidationClient는 4xx를 onStatus로 잡아 NOT_FOUND 결과로 변환하므로 4xx는 예외로 새지 않지만,
   * 브로드한 RestClientException 대신 HttpServerErrorException을 명시해 4xx(HttpClientErrorException)가 서킷을
   * 트립하지 못하도록 좁힌다.
   */
  @Bean
  public CircuitBreaker orderCircuitBreaker(
      CircuitBreakerRegistry registry,
      @Value("${order.cb.slow-call-ms:1500}") long slowCallMs) {
    CircuitBreakerConfig config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(WINDOW_SIZE)
            .minimumNumberOfCalls(MIN_CALLS)
            // 내부 API라 보수화 불요(toss는 외부 PG라 평시 성공률 99.9% 기준 30으로 낮춤). 여기는 50 고정 유지.
            .failureRateThreshold(50f)
            .slowCallDurationThreshold(Duration.ofMillis(slowCallMs))
            .slowCallRateThreshold(SLOW_CALL_RATE)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .recordExceptions(
                IllegalStateException.class,
                ResourceAccessException.class,
                HttpServerErrorException.class)
            .build();
    return registry.circuitBreaker("order", config);
  }

  /**
   * 토스 압살 방지 리미터(웹훅 재조회·대사 증폭 경로 있음). permits/s와 대기시간은 env, refresh 1s 고정.
   * limitForPeriod는 인스턴스별 정적 분할값(레플리카 수 변경 시 env로 재계산).
   */
  @Bean
  public RateLimiter tossRateLimiter(
      RateLimiterRegistry registry,
      @Value("${toss.rl.permits-per-sec:10}") int permitsPerSec,
      @Value("${toss.rl.timeout-ms:500}") long timeoutMs) {
    RateLimiterConfig config =
        RateLimiterConfig.custom()
            .limitForPeriod(permitsPerSec)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofMillis(timeoutMs))
            .build();
    return registry.rateLimiter("toss", config);
  }

  @Bean
  public TaggedCircuitBreakerMetrics circuitBreakerMetrics(
      CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
    TaggedCircuitBreakerMetrics metrics =
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }

  @Bean
  public TaggedRateLimiterMetrics rateLimiterMetrics(
      RateLimiterRegistry registry, MeterRegistry meterRegistry) {
    TaggedRateLimiterMetrics metrics = TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry);
    metrics.bindTo(meterRegistry);
    return metrics;
  }
}

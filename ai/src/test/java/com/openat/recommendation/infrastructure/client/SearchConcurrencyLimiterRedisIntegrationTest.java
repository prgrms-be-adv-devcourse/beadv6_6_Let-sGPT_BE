package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * java.util.concurrent.Semaphore는 JVM 인스턴스 하나에만 적용되는 로컬 제한이라, 파드가 여러 개면 검색 서비스에는 파드 수 x
 * max-concurrency 만큼 부하가 간다(PR #313에서 지적됨). 이 테스트는 Redis만 공유하고 커넥션은 서로 다른, 즉 서로 다른 애플리케이션 인스턴스를 흉내
 * 낸 두 개의 리미터가 permit을 진짜로 나눠 쓰는지를 검증한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class SearchConcurrencyLimiterRedisIntegrationTest {

  // production 코드의 SearchConcurrencyLimiter.PERMITS_KEY와 반드시 같아야 한다 — 테스트 간 잔여 permit 청소용.
  private static final String PERMITS_KEY = "recommendation:search:permits";

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  private LettuceConnectionFactory connectionFactoryA;
  private LettuceConnectionFactory connectionFactoryB;

  @BeforeEach
  void clearPermits() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    try {
      new StringRedisTemplate(factory).delete(PERMITS_KEY);
    } finally {
      factory.destroy();
    }
  }

  @AfterEach
  void closeConnectionFactories() {
    if (connectionFactoryA != null) {
      connectionFactoryA.destroy();
    }
    if (connectionFactoryB != null) {
      connectionFactoryB.destroy();
    }
  }

  private StringRedisTemplate newTemplate(boolean useFactoryA) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    if (useFactoryA) {
      connectionFactoryA = factory;
    } else {
      connectionFactoryB = factory;
    }
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return template;
  }

  @Test
  void tryAcquire_whenPermitAlreadyHeld_waitsForAcquireTimeoutThenFails() {
    StringRedisTemplate template = newTemplate(true);
    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(5), Duration.ofSeconds(1));
    SearchConcurrencyLimiter waiter =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(5), Duration.ofMillis(300));

    assertThat(holder.tryAcquire("holder")).isTrue();
    long start = System.currentTimeMillis();
    boolean acquired = waiter.tryAcquire("waiter");
    long elapsed = System.currentTimeMillis() - start;

    assertThat(acquired).isFalse();
    assertThat(elapsed).isGreaterThanOrEqualTo(280L);
    holder.release("holder");
  }

  @Test
  void tryAcquire_afterHolderReleases_letsWaiterAcquire() throws Exception {
    StringRedisTemplate template = newTemplate(true);
    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(5), Duration.ofSeconds(1));
    SearchConcurrencyLimiter waiter =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(5), Duration.ofSeconds(2));

    assertThat(holder.tryAcquire("holder")).isTrue();
    CompletableFuture<Boolean> waiterAcquired =
        CompletableFuture.supplyAsync(
            () -> waiter.tryAcquire("waiter"), Executors.newVirtualThreadPerTaskExecutor());
    Thread.sleep(150);
    assertThat(waiterAcquired.isDone()).isFalse();
    holder.release("holder");

    assertThat(waiterAcquired.get()).isTrue();
    waiter.release("waiter");
  }

  @Test
  void tryAcquire_acrossTwoIndependentInstancesSharingRedis_isGloballyExclusive() {
    // 서로 다른 커넥션(=서로 다른 파드를 흉내 낸 인스턴스)에서도 같은 Redis에 대해선 permit이 하나뿐이다.
    StringRedisTemplate templateOnPodA = newTemplate(true);
    StringRedisTemplate templateOnPodB = newTemplate(false);
    SearchConcurrencyLimiter limiterOnPodA =
        new SearchConcurrencyLimiter(
            templateOnPodA, 1, Duration.ofSeconds(5), Duration.ofSeconds(1));
    SearchConcurrencyLimiter limiterOnPodB =
        new SearchConcurrencyLimiter(
            templateOnPodB, 1, Duration.ofSeconds(5), Duration.ofMillis(300));

    assertThat(limiterOnPodA.tryAcquire("pod-a-permit")).isTrue();
    boolean acquiredByPodB = limiterOnPodB.tryAcquire("pod-b-permit");

    assertThat(acquiredByPodB).isFalse();

    limiterOnPodA.release("pod-a-permit");
    assertThat(limiterOnPodB.tryAcquire("pod-b-permit-retry")).isTrue();
    limiterOnPodB.release("pod-b-permit-retry");
  }

  @Test
  void tryAcquire_afterPermitTtlExpiresWithoutRelease_recoversAutomatically() throws Exception {
    StringRedisTemplate template = newTemplate(true);
    // 리미터를 거쳐 acquire하면 갱신 스레드가 계속 TTL을 늘려 크래시를 흉내 낼 수 없으므로,
    // 리미터 없이 permit을 직접 심어 "쓰다가 죽어서 다시는 갱신되지 않는 permit"을 재현한다.
    template.opsForZSet().add(PERMITS_KEY, "crashed-permit", System.currentTimeMillis());
    SearchConcurrencyLimiter survivor =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(200), Duration.ofMillis(0));

    assertThat(survivor.tryAcquire("immediate-retry")).isFalse();

    Thread.sleep(250);

    assertThat(survivor.tryAcquire("after-ttl-expiry")).isTrue();
    survivor.release("after-ttl-expiry");
  }

  @Test
  void tryAcquire_whileHeldLongerThanPermitTtl_renewsLeaseSoOtherAcquireStaysBlocked()
      throws Exception {
    StringRedisTemplate template = newTemplate(true);
    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(300), Duration.ofMillis(100));
    SearchConcurrencyLimiter other =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(300), Duration.ofMillis(100));

    assertThat(holder.tryAcquire("holder")).isTrue();
    // permitTtl(300ms)보다 오래 붙잡는다. 갱신이 없다면 이 사이 permit이 청소돼 other가 획득했을 것이다.
    Thread.sleep(700);
    assertThat(other.tryAcquire("other-while-held")).isFalse();

    holder.release("holder");
  }

  @Test
  void tryAcquire_duringRenewalCycleGap_neverSucceedsForCompetitor() throws Exception {
    StringRedisTemplate template = newTemplate(true);
    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(200), Duration.ofMillis(100));
    SearchConcurrencyLimiter competitor =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(200), Duration.ofMillis(0));

    assertThat(holder.tryAcquire("holder")).isTrue();

    long deadline = System.currentTimeMillis() + 500;
    int attempts = 0;
    while (System.currentTimeMillis() < deadline) {
      attempts++;
      assertThat(competitor.tryAcquire("competitor-" + attempts)).isFalse();
    }
    assertThat(attempts).isGreaterThan(50);

    holder.release("holder");
  }

  @Test
  void renewLoop_survivesTransientRedisExceptionsAndKeepsPermitAlive() throws Exception {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    connectionFactoryA = factory;
    FlakyStringRedisTemplate template = new FlakyStringRedisTemplate(factory);
    template.afterPropertiesSet();

    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(300), Duration.ofMillis(100));
    SearchConcurrencyLimiter other =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(300), Duration.ofMillis(100));

    assertThat(holder.tryAcquire("holder")).isTrue();
    Thread.sleep(700);
    assertThat(other.tryAcquire("other-while-held")).isFalse();
    assertThat(template.renewFailures()).isGreaterThanOrEqualTo(2);

    holder.release("holder");
  }

  @Test
  void release_stopsRenewalThreadSoPermitDoesNotReappear() throws Exception {
    StringRedisTemplate template = newTemplate(true);
    SearchConcurrencyLimiter limiter =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(150), Duration.ofMillis(100));
    SearchConcurrencyLimiter other =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(150), Duration.ofMillis(100));

    assertThat(limiter.tryAcquire("permit")).isTrue();
    Thread.sleep(120); // 갱신 간격(ttl/3=50ms)보다 길게 기다려 갱신이 최소 한 번은 일어나게 한다.
    limiter.release("permit");

    // 갱신 스레드가 release 뒤에도 살아있다면 여기서 한 번 더 갱신해 permit을 되살렸을 것이다.
    Thread.sleep(120);
    assertThat(other.tryAcquire("other")).isTrue();
    other.release("other");
  }

  @Test
  void release_whileRenewalCallIsBlocked_returnsQuicklyWithoutWaitingForRenewalThread()
      throws Exception {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    connectionFactoryA = factory;
    StuckRenewalStringRedisTemplate template = new StuckRenewalStringRedisTemplate(factory);
    template.afterPropertiesSet();

    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(300), Duration.ofMillis(100));

    assertThat(holder.tryAcquire("holder")).isTrue();
    assertThat(template.renewalStarted().await(2, TimeUnit.SECONDS)).isTrue();

    // 갱신 호출이 1300ms(STUCK_BLOCK_MILLIS) 동안 블로킹 중인 상태에서 release를 호출한다.
    // join이 남아있다면 releaseElapsed가 900ms 이상으로 튄다.
    long releaseStart = System.currentTimeMillis();
    holder.release("holder");
    long releaseElapsed = System.currentTimeMillis() - releaseStart;

    assertThat(releaseElapsed).isLessThan(100L);

    // 갱신 스레드가 완전히 끝날 때까지 기다려 다음 테스트로 넘어가기 전에 정리한다.
    Thread.sleep(1500);
  }

  @Test
  void release_whenRenewalCallIsStuck_letsCompetitorAcquireImmediatelyAndStopsRetrying()
      throws Exception {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    connectionFactoryA = factory;
    StuckRenewalStringRedisTemplate template = new StuckRenewalStringRedisTemplate(factory);
    template.afterPropertiesSet();

    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(300), Duration.ofMillis(100));
    SearchConcurrencyLimiter competitor =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(10), Duration.ofMillis(100));

    assertThat(holder.tryAcquire("holder")).isTrue();
    assertThat(template.renewalStarted().await(2, TimeUnit.SECONDS)).isTrue();

    holder.release("holder");
    // release는 갱신 스레드가 아직 블로킹 중이어도 releaseScript부터 동기 실행하므로,
    // 경쟁자는 갱신 스레드 종료를 기다리지 않고 즉시 permit을 획득할 수 있어야 한다.
    assertThat(competitor.tryAcquire("competitor")).isTrue();
    competitor.release("competitor");

    // 갱신 스레드가 STUCK_BLOCK_MILLIS(1300ms) 뒤 예외를 던지고 취소 플래그를 보고 종료할 때까지 기다린다.
    Thread.sleep(1600);
    assertThat(template.renewAttempts()).isEqualTo(1);
  }

  @Test
  void renewLoop_whenLeaseAlreadyLost_stopsRenewingInsteadOfRetryingForever() throws Exception {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    connectionFactoryA = factory;
    CountingRenewStringRedisTemplate template = new CountingRenewStringRedisTemplate(factory);
    template.afterPropertiesSet();

    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(200), Duration.ofMillis(100));

    assertThat(holder.tryAcquire("holder")).isTrue();
    template.opsForZSet().remove(PERMITS_KEY, "holder");

    Thread.sleep(300);
    int attemptsAfterLeaseLost = template.renewAttempts();
    assertThat(attemptsAfterLeaseLost).isGreaterThanOrEqualTo(1);

    Thread.sleep(500);
    assertThat(template.renewAttempts()).isEqualTo(attemptsAfterLeaseLost);

    holder.release("holder");
  }

  @Test
  void tryAcquire_whenContended_pollsFewerTimesThanFixedThirtyMillisIntervalWould()
      throws Exception {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    factory.afterPropertiesSet();
    connectionFactoryA = factory;
    CountingStringRedisTemplate template = new CountingStringRedisTemplate(factory);

    SearchConcurrencyLimiter holder =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(5), Duration.ofMillis(0));
    assertThat(holder.tryAcquire("holder")).isTrue();

    long acquireTimeoutMillis = 600L;
    SearchConcurrencyLimiter waiter =
        new SearchConcurrencyLimiter(
            template, 1, Duration.ofSeconds(5), Duration.ofMillis(acquireTimeoutMillis));

    assertThat(waiter.tryAcquire("waiter")).isFalse();

    // 고정 30ms 폴링이었다면 600ms 안에 최대 20회 시도했을 것이다. 지수 백오프는 그보다 훨씬 적게 시도해야 한다.
    long fixedIntervalUpperBound = acquireTimeoutMillis / 30L;
    assertThat(template.executions()).isLessThan((int) fixedIntervalUpperBound);

    holder.release("holder");
  }

  private static final class CountingStringRedisTemplate extends StringRedisTemplate {

    private final AtomicInteger executions = new AtomicInteger();

    CountingStringRedisTemplate(RedisConnectionFactory connectionFactory) {
      super(connectionFactory);
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      executions.incrementAndGet();
      return super.execute(script, keys, args);
    }

    int executions() {
      return executions.get();
    }
  }

  private static final class FlakyStringRedisTemplate extends StringRedisTemplate {

    // acquire=4개 인자, release=1개 — renew만 3개라 이걸로 구분한다. 스크립트 인자 수가 바뀌면 갱신해야 한다.
    private static final int RENEW_SCRIPT_ARG_COUNT = 3;

    private final AtomicInteger renewAttempts = new AtomicInteger();
    private final AtomicInteger renewFailures = new AtomicInteger();

    FlakyStringRedisTemplate(RedisConnectionFactory connectionFactory) {
      super(connectionFactory);
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      if (args.length == RENEW_SCRIPT_ARG_COUNT && renewAttempts.incrementAndGet() % 2 == 1) {
        renewFailures.incrementAndGet();
        throw new RuntimeException("simulated transient redis failure");
      }
      return super.execute(script, keys, args);
    }

    int renewFailures() {
      return renewFailures.get();
    }
  }

  /**
   * 갱신 호출마다 interrupt를 흡수하며 STUCK_BLOCK_MILLIS만큼 버틴 뒤 RuntimeException을 던져, "interrupt가 일반
   * RuntimeException으로 소비되는" 상황을 흉내 낸다.
   */
  private static final class StuckRenewalStringRedisTemplate extends StringRedisTemplate {

    private static final int RENEW_SCRIPT_ARG_COUNT = 3;
    private static final long STUCK_BLOCK_MILLIS = 1300L;

    private final CountDownLatch renewalStarted = new CountDownLatch(1);
    private final AtomicInteger renewAttempts = new AtomicInteger();

    StuckRenewalStringRedisTemplate(RedisConnectionFactory connectionFactory) {
      super(connectionFactory);
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      if (args.length == RENEW_SCRIPT_ARG_COUNT) {
        renewAttempts.incrementAndGet();
        renewalStarted.countDown();
        long deadline = System.currentTimeMillis() + STUCK_BLOCK_MILLIS;
        while (System.currentTimeMillis() < deadline) {
          try {
            Thread.sleep(Math.max(1, deadline - System.currentTimeMillis()));
          } catch (InterruptedException interrupted) {
            // 소켓 레벨 interrupt 흡수를 흉내 낸다: 예외를 삼키고 원래 블로킹 시간만큼 계속 버틴다.
          }
        }
        throw new RuntimeException("simulated stuck redis call swallowing interrupt");
      }
      return super.execute(script, keys, args);
    }

    CountDownLatch renewalStarted() {
      return renewalStarted;
    }

    int renewAttempts() {
      return renewAttempts.get();
    }
  }

  private static final class CountingRenewStringRedisTemplate extends StringRedisTemplate {

    private static final int RENEW_SCRIPT_ARG_COUNT = 3;

    private final AtomicInteger renewAttempts = new AtomicInteger();

    CountingRenewStringRedisTemplate(RedisConnectionFactory connectionFactory) {
      super(connectionFactory);
    }

    @Override
    public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
      if (args.length == RENEW_SCRIPT_ARG_COUNT) {
        renewAttempts.incrementAndGet();
      }
      return super.execute(script, keys, args);
    }

    int renewAttempts() {
      return renewAttempts.get();
    }
  }
}

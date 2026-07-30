package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
  }

  @Test
  void tryAcquire_afterPermitTtlExpiresWithoutRelease_recoversAutomatically() throws Exception {
    StringRedisTemplate template = newTemplate(true);
    SearchConcurrencyLimiter crashed =
        new SearchConcurrencyLimiter(template, 1, Duration.ofMillis(200), Duration.ofMillis(0));
    SearchConcurrencyLimiter survivor =
        new SearchConcurrencyLimiter(template, 1, Duration.ofSeconds(5), Duration.ofMillis(0));

    assertThat(crashed.tryAcquire("crashed-permit")).isTrue();
    assertThat(survivor.tryAcquire("immediate-retry")).isFalse();

    Thread.sleep(250);

    assertThat(survivor.tryAcquire("after-ttl-expiry")).isTrue();
  }
}

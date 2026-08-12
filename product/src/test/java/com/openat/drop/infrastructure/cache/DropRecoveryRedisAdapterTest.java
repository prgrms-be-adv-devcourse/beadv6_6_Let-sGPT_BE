package com.openat.drop.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.common.exception.BusinessException;
import com.openat.config.DropProperties;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.repository.DropCacheState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("드롭 복구 게이트(Redis) 어댑터")
class DropRecoveryRedisAdapterTest {

  private static final Duration LEASE = Duration.ofSeconds(30);

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static LettuceConnectionFactory firstConnectionFactory;
  static LettuceConnectionFactory secondConnectionFactory;
  static StringRedisTemplate redisTemplate;
  static DropRecoveryRedisAdapter firstReplica;
  static DropRecoveryRedisAdapter secondReplica;
  static DropCacheRedisAdaptor cacheAdaptor;

  @BeforeAll
  static void init() {
    firstConnectionFactory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    secondConnectionFactory =
        new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    firstConnectionFactory.afterPropertiesSet();
    secondConnectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(firstConnectionFactory);
    redisTemplate.afterPropertiesSet();
    StringRedisTemplate secondTemplate = new StringRedisTemplate(secondConnectionFactory);
    secondTemplate.afterPropertiesSet();
    firstReplica = new DropRecoveryRedisAdapter(redisTemplate);
    secondReplica = new DropRecoveryRedisAdapter(secondTemplate);
    cacheAdaptor =
        new DropCacheRedisAdaptor(
            redisTemplate,
            new DropProperties(
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                Duration.ofDays(7),
                Duration.ofHours(1),
                Duration.ofSeconds(10)));
  }

  @AfterAll
  static void cleanup() {
    firstConnectionFactory.destroy();
    secondConnectionFactory.destroy();
  }

  @BeforeEach
  void flush() {
    redisTemplate.execute(
        (RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  @Test
  @DisplayName("서로 다른 연결의 변경과 복구가 경합하면 정확히 하나만 진입한다")
  void concurrentReplicas_changeAndRecovery_areAtomicallyExclusive() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int index = 0; index < 100; index++) {
        UUID dropId = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> change =
            executor.submit(
                () -> {
                  await(start);
                  return firstReplica.beginChange(dropId, attempt);
                });
        Future<Boolean> recovery =
            executor.submit(
                () -> {
                  await(start);
                  return secondReplica.beginRecovery(dropId, owner, LEASE);
                });
        start.countDown();
        boolean changeAccepted = change.get(10, TimeUnit.SECONDS);
        boolean recoveryAccepted = recovery.get(10, TimeUnit.SECONDS);

        assertThat(changeAccepted ^ recoveryAccepted).isTrue();
        if (changeAccepted) {
          assertThat(redisTemplate.opsForSet().members(inflightKey(dropId)))
              .containsExactly(attempt.toString());
          assertThat(redisTemplate.hasKey(recoveryKey(dropId))).isFalse();
        } else {
          assertThat(redisTemplate.opsForValue().get(recoveryKey(dropId)))
              .isEqualTo(owner.toString());
          assertThat(redisTemplate.hasKey(inflightKey(dropId))).isFalse();
        }
        firstReplica.completeChange(dropId, attempt);
        secondReplica.completeRecovery(dropId, owner);
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("동시 변경은 허용하되 각각의 attempt가 완료되기 전에는 복구를 거절한다")
  void concurrentChanges_requireBothDistinctAttemptsToComplete() throws Exception {
    UUID dropId = UUID.randomUUID();
    UUID firstAttempt = UUID.randomUUID();
    UUID secondAttempt = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(
              () -> {
                await(start);
                return firstReplica.beginChange(dropId, firstAttempt);
              });
      Future<Boolean> second =
          executor.submit(
              () -> {
                await(start);
                return secondReplica.beginChange(dropId, secondAttempt);
              });
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }
    assertThat(redisTemplate.opsForSet().members(inflightKey(dropId)))
        .containsExactlyInAnyOrder(firstAttempt.toString(), secondAttempt.toString());
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isFalse();

    firstReplica.completeChange(dropId, UUID.randomUUID());
    firstReplica.completeChange(dropId, firstAttempt);
    firstReplica.completeChange(dropId, firstAttempt);
    assertThat(redisTemplate.opsForSet().members(inflightKey(dropId)))
        .containsExactly(secondAttempt.toString());
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isFalse();

    secondReplica.completeChange(dropId, secondAttempt);
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isTrue();
  }

  @Test
  @DisplayName("복구 중에는 다른 레플리카의 변경과 복구를 막고 다른 드롭은 허용한다")
  void recovery_blocksSameDropAcrossReplicas_untilOwnerCompletes() {
    UUID dropId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isTrue();

    assertThat(secondReplica.beginChange(dropId, attempt)).isFalse();
    assertThat(secondReplica.beginRecovery(dropId, UUID.randomUUID(), LEASE)).isFalse();
    assertThat(secondReplica.beginChange(UUID.randomUUID(), UUID.randomUUID())).isTrue();
    assertThat(redisTemplate.hasKey(inflightKey(dropId))).isFalse();

    firstReplica.completeRecovery(dropId, owner);
    assertThat(secondReplica.beginChange(dropId, attempt)).isTrue();
  }

  @Test
  @DisplayName("만료된 owner는 새 owner의 lease를 해제하거나 스냅샷을 발행할 수 없다")
  void expiredOwner_cannotReleaseOrPublishOverNewOwner() throws Exception {
    UUID dropId = UUID.randomUUID();
    UUID oldOwner = UUID.randomUUID();
    UUID newOwner = UUID.randomUUID();
    assertThat(firstReplica.beginRecovery(dropId, oldOwner, Duration.ofMillis(40))).isTrue();
    awaitKeyExpired(recoveryKey(dropId));
    assertThat(secondReplica.beginRecovery(dropId, newOwner, LEASE)).isTrue();

    firstReplica.completeRecovery(dropId, oldOwner);
    assertThat(redisTemplate.opsForValue().get(recoveryKey(dropId))).isEqualTo(newOwner.toString());
    assertWarmRejected(state(dropId, 99, Map.of()), oldOwner);
    assertThat(redisTemplate.hasKey(dropKey(dropId))).isFalse();
    assertThat(firstReplica.beginChange(dropId, UUID.randomUUID())).isFalse();

    cacheAdaptor.warm(state(dropId, 7, Map.of()), newOwner);
    assertThat(cacheAdaptor.findRemaining(List.of(dropId))).containsEntry(dropId, 7L);
    secondReplica.completeRecovery(dropId, newOwner);
    assertThat(firstReplica.beginChange(dropId, UUID.randomUUID())).isTrue();
  }

  @Test
  @DisplayName("lease가 없거나 owner가 다르면 기존 캐시가 있어도 워밍을 거절한다")
  void warm_requiresCurrentOwner_evenWhenCacheExists() {
    UUID dropId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    DropCacheState snapshot = state(dropId, 7, Map.of(UUID.randomUUID(), 3L));
    assertWarmRejected(snapshot, owner);
    assertThat(redisTemplate.hasKey(dropKey(dropId))).isFalse();
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isTrue();
    cacheAdaptor.warm(snapshot, owner);
    Map<Object, Object> before = redisTemplate.opsForHash().entries(dropKey(dropId));
    Map<Object, Object> buyersBefore = redisTemplate.opsForHash().entries(buyersKey(dropId));

    assertWarmRejected(state(dropId, 99, Map.of()), UUID.randomUUID());
    firstReplica.completeRecovery(dropId, owner);
    assertWarmRejected(state(dropId, 99, Map.of()), owner);

    assertThat(redisTemplate.opsForHash().entries(dropKey(dropId))).isEqualTo(before);
    assertThat(redisTemplate.opsForHash().entries(buyersKey(dropId))).isEqualTo(buyersBefore);
  }

  @Test
  @DisplayName("진행 중 변경 표식은 TTL이 없고 캐시 만료 및 늦은 워밍으로 제거되지 않는다")
  void inflight_hasNoTtl_andSurvivesCacheExpiryAndRejectedWarm() throws Exception {
    UUID dropId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isTrue();
    cacheAdaptor.warm(state(dropId, 10, Map.of(UUID.randomUUID(), 1L)), owner);
    firstReplica.completeRecovery(dropId, owner);
    assertThat(secondReplica.beginChange(dropId, attempt)).isTrue();
    redisTemplate.expire(dropKey(dropId), Duration.ofMillis(40));
    redisTemplate.expire(buyersKey(dropId), Duration.ofMillis(40));
    awaitKeyExpired(dropKey(dropId));
    awaitKeyExpired(buyersKey(dropId));

    assertThat(redisTemplate.hasKey(dropKey(dropId))).isFalse();
    assertWarmRejected(state(dropId, 100, Map.of()), owner);
    cacheAdaptor.evictBeforeOpen(dropId);
    assertInflightRetained(dropId, attempt);
    assertThat(firstReplica.beginRecovery(dropId, UUID.randomUUID(), LEASE)).isFalse();

    secondReplica.completeChange(dropId, attempt);
    assertThat(firstReplica.beginRecovery(dropId, UUID.randomUUID(), LEASE)).isTrue();
  }

  @Test
  @DisplayName("오픈 전 캐시를 제거해도 진행 중 변경 표식은 보존한다")
  void evictBeforeOpen_doesNotDeleteInflightMarker() {
    UUID dropId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID attempt = UUID.randomUUID();
    assertThat(firstReplica.beginRecovery(dropId, owner, LEASE)).isTrue();
    cacheAdaptor.warm(
        new DropCacheState(
            dropId, 10, Instant.now().plusSeconds(60), null, null, Map.of(UUID.randomUUID(), 1L)),
        owner);
    firstReplica.completeRecovery(dropId, owner);
    assertThat(secondReplica.beginChange(dropId, attempt)).isTrue();

    assertThat(cacheAdaptor.evictBeforeOpen(dropId)).isTrue();

    assertThat(redisTemplate.hasKey(dropKey(dropId))).isFalse();
    assertThat(redisTemplate.hasKey(buyersKey(dropId))).isFalse();
    assertInflightRetained(dropId, attempt);
    assertThat(firstReplica.beginRecovery(dropId, UUID.randomUUID(), LEASE)).isFalse();
  }

  @Test
  @DisplayName("복구 lease는 1밀리초 이상이어야 한다")
  void beginRecovery_rejectsInvalidLease_withoutCreatingKey() {
    UUID dropId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    assertThatThrownBy(() -> firstReplica.beginRecovery(dropId, owner, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> firstReplica.beginRecovery(dropId, owner, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> firstReplica.beginRecovery(dropId, owner, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> firstReplica.beginRecovery(dropId, owner, Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(redisTemplate.hasKey(recoveryKey(dropId))).isFalse();
  }

  private void assertWarmRejected(DropCacheState snapshot, UUID owner) {
    assertThatThrownBy(() -> cacheAdaptor.warm(snapshot, owner))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error ->
                assertThat(error.getErrorCode()).isEqualTo(DropErrorCode.STOCK_CHANGE_IN_PROGRESS));
  }

  private void assertInflightRetained(UUID dropId, UUID attempt) {
    assertThat(redisTemplate.opsForSet().members(inflightKey(dropId)))
        .containsExactly(attempt.toString());
    assertThat(redisTemplate.getExpire(inflightKey(dropId), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
  }

  private void awaitKeyExpired(String key) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (Boolean.TRUE.equals(redisTemplate.hasKey(key)) && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(redisTemplate.hasKey(key)).isFalse();
  }

  private void await(CountDownLatch start) throws InterruptedException {
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
  }

  private DropCacheState state(UUID dropId, long remaining, Map<UUID, Long> buyers) {
    return new DropCacheState(
        dropId, remaining, Instant.now().minusSeconds(60), null, null, buyers);
  }

  private String dropKey(UUID dropId) {
    return "drop:" + dropId;
  }

  private String buyersKey(UUID dropId) {
    return dropKey(dropId) + ":buyers";
  }

  private String inflightKey(UUID dropId) {
    return DropRecoveryRedisAdapter.inflightKey(dropId);
  }

  private String recoveryKey(UUID dropId) {
    return DropRecoveryRedisAdapter.recoveryKey(dropId);
  }
}

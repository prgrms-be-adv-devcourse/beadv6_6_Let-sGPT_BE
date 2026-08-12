package com.openat.drop.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.config.DropProperties;
import com.openat.drop.domain.model.StockCommandStatus;
import com.openat.drop.domain.repository.DropCacheState;
import com.openat.drop.domain.repository.StockCommandResult;
import com.openat.drop.domain.repository.StockMutation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("드롭 캐시(Redis) 어댑터")
class DropCacheRedisAdaptorTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static LettuceConnectionFactory connectionFactory;
  static StringRedisTemplate redisTemplate;
  static DropCacheRedisAdaptor adaptor;
  static DropRecoveryRedisAdapter recoveryAdaptor;

  @BeforeAll
  static void init() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    DropProperties properties =
        new DropProperties(
            Duration.ofMinutes(5),
            Duration.ofMinutes(10),
            Duration.ofDays(7),
            Duration.ofHours(1),
            Duration.ofSeconds(10));
    adaptor = new DropCacheRedisAdaptor(redisTemplate, properties);
    recoveryAdaptor = new DropRecoveryRedisAdapter(redisTemplate);
  }

  @AfterAll
  static void cleanup() {
    connectionFactory.destroy();
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
  @DisplayName("오픈된 드롭을 차감하면 OK와 줄어든 잔여를 반환한다")
  void deduct_openWithStock_decrements() {
    // given
    UUID dropId = UUID.randomUUID();
    warm(openState(dropId, 10, null));

    // when
    StockCommandResult result = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 3);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.OK);
    assertThat(result.remaining()).isEqualTo(7);
  }

  @Test
  @DisplayName("같은 주문으로 다시 차감하면 DUPLICATE를 반환하고 재차감하지 않는다")
  void deduct_sameOrder_isIdempotent() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    warm(openState(dropId, 10, null));
    deduct(dropId, orderId, buyerId, 3);

    // when
    StockCommandResult retry = deduct(dropId, orderId, buyerId, 3);

    // then
    assertThat(retry.status()).isEqualTo(StockCommandStatus.DUPLICATE);
    assertThat(retry.remaining()).isEqualTo(7);
  }

  @Test
  @DisplayName("워밍되지 않은 드롭이면 NOT_CACHED를 반환한다")
  void deduct_notCached_returnsNotCached() {
    // when
    StockCommandResult result = deduct(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.NOT_CACHED);
  }

  @Test
  @DisplayName("오픈 시각 전이면 NOT_OPEN을 반환한다")
  void deduct_beforeOpen_returnsNotOpen() {
    // given
    UUID dropId = UUID.randomUUID();
    Instant openAt = Instant.now().plusSeconds(600);
    warm(new DropCacheState(dropId, 10, openAt, null, null, Map.of()));

    // when
    StockCommandResult result = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.NOT_OPEN);
  }

  @Test
  @DisplayName("종료 시각이 지났으면 CLOSED를 반환한다")
  void deduct_afterClose_returnsClosed() {
    // given
    UUID dropId = UUID.randomUUID();
    Instant openAt = Instant.now().minusSeconds(120);
    Instant closeAt = Instant.now().minusSeconds(60);
    warm(new DropCacheState(dropId, 10, openAt, closeAt, null, Map.of()));

    // when
    StockCommandResult result = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.CLOSED);
  }

  @Test
  @DisplayName("1인 한도를 초과하면 LIMIT_EXCEEDED를 반환한다")
  void deduct_overLimit_returnsLimitExceeded() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    warm(openState(dropId, 10, 1));
    deduct(dropId, UUID.randomUUID(), buyerId, 1);

    // when
    StockCommandResult result = deduct(dropId, UUID.randomUUID(), buyerId, 1);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.LIMIT_EXCEEDED);
  }

  @Test
  @DisplayName("잔여보다 많이 요청하면 SOLD_OUT을 반환하고 차감하지 않는다")
  void deduct_insufficientStock_returnsSoldOut() {
    // given
    UUID dropId = UUID.randomUUID();
    warm(openState(dropId, 1, null));

    // when
    StockCommandResult result = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 2);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.SOLD_OUT);
    assertThat(result.remaining()).isEqualTo(1);
  }

  @Test
  @DisplayName("롤백하면 잔여와 구매자 카운터가 복원된다")
  void rollback_restoresRemaining() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    warm(openState(dropId, 5, null));
    deduct(dropId, orderId, buyerId, 2);

    // when
    StockCommandResult result = rollback(dropId, orderId, buyerId, 2);

    // then
    assertThat(result.status()).isEqualTo(StockCommandStatus.OK);
    assertThat(result.remaining()).isEqualTo(5);
    Object remaining = redisTemplate.opsForHash().get("drop:" + dropId, "remaining");
    assertThat(remaining).isEqualTo("5");
  }

  @Test
  @DisplayName("차감 보상은 역연산 뒤 실제 캐시 잔여를 반환한다")
  void compensateDeduct_returnsActualRemaining() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    StockMutation mutation = new StockMutation(dropId, orderId, buyerId, 2);
    warm(openState(dropId, 5, null));
    adaptor.deduct(mutation);

    // when
    Optional<Long> remaining = adaptor.compensateDeduct(mutation);

    // then
    assertThat(remaining).contains(5L);
    assertThat(redisTemplate.opsForHash().get("drop:" + dropId, "remaining")).isEqualTo("5");
  }

  @Test
  @DisplayName("롤백 보상은 역연산 뒤 실제 캐시 잔여를 반환한다")
  void compensateRollback_returnsActualRemaining() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    StockMutation mutation = new StockMutation(dropId, orderId, buyerId, 2);
    warm(openState(dropId, 5, null));
    adaptor.deduct(mutation);
    adaptor.rollback(mutation);

    // when
    Optional<Long> remaining = adaptor.compensateRollback(mutation);

    // then
    assertThat(remaining).contains(3L);
    assertThat(redisTemplate.opsForHash().get("drop:" + dropId, "remaining")).isEqualTo("3");
  }

  @Test
  @DisplayName("동시 차감 요청에도 재고를 초과 판매하지 않는다")
  void deduct_concurrentRequests_neverOversells() throws InterruptedException {
    // given
    UUID dropId = UUID.randomUUID();
    int stock = 100;
    int requests = 300;
    warm(openState(dropId, stock, null));
    ExecutorService executor = Executors.newFixedThreadPool(32);
    CountDownLatch done = new CountDownLatch(requests);
    AtomicInteger success = new AtomicInteger();

    // when
    for (int i = 0; i < requests; i++) {
      executor.submit(
          () -> {
            try {
              StockCommandResult result = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1);
              if (result.status() == StockCommandStatus.OK) {
                success.incrementAndGet();
              }
            } finally {
              done.countDown();
            }
          });
    }
    done.await();
    executor.shutdown();

    // then
    assertThat(success.get()).isEqualTo(stock);
    StockCommandResult afterSoldOut = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1);
    assertThat(afterSoldOut.status()).isEqualTo(StockCommandStatus.SOLD_OUT);
    assertThat(afterSoldOut.remaining()).isZero();
  }

  @Test
  @DisplayName("종료 표시하면 신규 주문은 DROP_CLOSED, 이미 선점한 주문은 멱등 통과한다")
  void markClosed_blocksNewButLetsInflightThrough() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID inflightOrder = UUID.randomUUID();
    UUID buyer = UUID.randomUUID();
    warm(openState(dropId, 10, null));
    deduct(dropId, inflightOrder, buyer, 1);

    // when
    adaptor.markClosed(dropId);

    // then
    StockCommandResult fresh = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1);
    assertThat(fresh.status()).isEqualTo(StockCommandStatus.CLOSED);
    StockCommandResult retry = deduct(dropId, inflightOrder, buyer, 1);
    assertThat(retry.status()).isEqualTo(StockCommandStatus.DUPLICATE);
  }

  @Test
  @DisplayName("워밍은 drop과 buyers를 권위 있는 스냅샷으로 완전 교체한다")
  void warm_replacesDropAndBuyersSnapshot() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID retainedBuyer = UUID.randomUUID();
    UUID removedBuyer = UUID.randomUUID();
    UUID newBuyer = UUID.randomUUID();
    Instant openAt = now().minusSeconds(60);
    Instant closeAt = now().plusSeconds(3600);
    warm(
        new DropCacheState(
            dropId,
            1,
            openAt.minusSeconds(60),
            closeAt.plusSeconds(60),
            9,
            Map.of(retainedBuyer, 4L, removedBuyer, 2L)));

    // when
    warm(
        new DropCacheState(dropId, 8, openAt, closeAt, 3, Map.of(retainedBuyer, 1L, newBuyer, 2L)));

    // then
    assertThat(redisTemplate.opsForHash().entries("drop:" + dropId))
        .containsOnly(
            Map.entry("remaining", "8"),
            Map.entry("openAt", Long.toString(openAt.toEpochMilli())),
            Map.entry("closeAt", Long.toString(closeAt.toEpochMilli())),
            Map.entry("limitPerUser", "3"));
    assertThat(redisTemplate.opsForHash().entries("drop:" + dropId + ":buyers"))
        .containsOnly(
            Map.entry(retainedBuyer.toString(), "1"), Map.entry(newBuyer.toString(), "2"));
    long dropTtl = redisTemplate.getExpire("drop:" + dropId, TimeUnit.MILLISECONDS);
    long buyersTtl = redisTemplate.getExpire("drop:" + dropId + ":buyers", TimeUnit.MILLISECONDS);
    assertThat(Math.abs(dropTtl - buyersTtl)).isLessThan(100L);
  }

  @Test
  @DisplayName("구매자가 없는 권위 스냅샷은 기존 buyers 해시를 제거한다")
  void warm_emptyBuyers_removesExistingHash() {
    // given
    UUID dropId = UUID.randomUUID();
    warm(
        new DropCacheState(
            dropId, 9, now().minusSeconds(60), null, null, Map.of(UUID.randomUUID(), 1L)));

    // when
    warm(openState(dropId, 10, null));

    // then
    assertThat(redisTemplate.hasKey("drop:" + dropId + ":buyers")).isFalse();
  }

  @Test
  @DisplayName("동시 관찰자는 워밍 전후 스냅샷 중 하나만 본다")
  void warm_concurrentObservation_neverSeesPartialSnapshot() throws Exception {
    // given
    UUID dropId = UUID.randomUUID();
    UUID buyerA = UUID.randomUUID();
    UUID buyerB = UUID.randomUUID();
    DropCacheState stateA = state(dropId, 10, Map.of(buyerA, 1L));
    DropCacheState stateB = state(dropId, 20, Map.of(buyerB, 2L));
    warm(stateA);
    RedisScript<Long> observeScript =
        RedisScript.of(
            "local r=redis.call('HGET',KEYS[1],'remaining');"
                + "local a=redis.call('HGET',KEYS[2],ARGV[1]);"
                + "local b=redis.call('HGET',KEYS[2],ARGV[2]);"
                + "if r=='10' and a=='1' and not b then return 1 end;"
                + "if r=='20' and b=='2' and not a then return 1 end;"
                + "return 0",
            Long.class);
    AtomicBoolean partialObserved = new AtomicBoolean();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    // when
    Future<?> writer =
        executor.submit(
            () -> {
              await(start);
              for (int index = 0; index < 200; index++) {
                warm(index % 2 == 0 ? stateB : stateA);
              }
            });
    Future<?> reader =
        executor.submit(
            () -> {
              await(start);
              for (int index = 0; index < 400; index++) {
                Long valid =
                    redisTemplate.execute(
                        observeScript,
                        List.of("drop:" + dropId, "drop:" + dropId + ":buyers"),
                        buyerA.toString(),
                        buyerB.toString());
                if (valid == null || valid == 0) {
                  partialObserved.set(true);
                  return;
                }
              }
            });
    start.countDown();
    writer.get(10, TimeUnit.SECONDS);
    reader.get(10, TimeUnit.SECONDS);
    executor.shutdownNow();

    // then
    assertThat(partialObserved).isFalse();
  }

  @Test
  @DisplayName("종료 표시는 짧게 남은 drop과 buyers TTL을 drain 여유까지 연장한다")
  void markClosed_shortTtl_extendsDrainWindow() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    warm(state(dropId, 8, Map.of(buyerId, 2L)));
    redisTemplate.expire("drop:" + dropId, Duration.ofSeconds(2));
    redisTemplate.expire("drop:" + dropId + ":buyers", Duration.ofSeconds(2));

    // when
    adaptor.markClosed(dropId);

    // then
    assertThat(redisTemplate.getExpire("drop:" + dropId, TimeUnit.MILLISECONDS))
        .isGreaterThan(595_000L);
    assertThat(redisTemplate.getExpire("drop:" + dropId + ":buyers", TimeUnit.MILLISECONDS))
        .isGreaterThan(595_000L);
  }

  @Test
  @DisplayName("캐시 차단 시점에도 오픈 전이면 drop과 buyers를 함께 제거한다")
  void evictBeforeOpen_preOpen_evictsSnapshot() {
    UUID dropId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    warm(
        new DropCacheState(
            dropId, 8, Instant.now().plusSeconds(60), null, null, Map.of(buyerId, 2L)));

    boolean evicted = adaptor.evictBeforeOpen(dropId);

    assertThat(evicted).isTrue();
    assertThat(redisTemplate.hasKey("drop:" + dropId)).isFalse();
    assertThat(redisTemplate.hasKey("drop:" + dropId + ":buyers")).isFalse();
  }

  @Test
  @DisplayName("캐시 차단 시점에 이미 오픈됐으면 스냅샷을 보존하고 삭제를 거절한다")
  void evictBeforeOpen_opened_retainsSnapshot() {
    UUID dropId = UUID.randomUUID();
    warm(openState(dropId, 10, null));

    boolean evicted = adaptor.evictBeforeOpen(dropId);

    assertThat(evicted).isFalse();
    assertThat(redisTemplate.opsForHash().get("drop:" + dropId, "remaining")).isEqualTo("10");
    assertThat(deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1).status())
        .isEqualTo(StockCommandStatus.OK);
  }

  @Test
  @DisplayName("종료 롤백은 closeAt만 복구하고 drain 중 재고와 구매자 변경을 보존한다")
  void restoreCloseAt_preservesInflightRollbackMutation() {
    // given
    UUID dropId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    warm(state(dropId, 8, Map.of(buyerId, 2L)));
    adaptor.markClosed(dropId);
    rollback(dropId, UUID.randomUUID(), buyerId, 1);

    // when
    adaptor.restoreCloseAt(dropId, null);

    // then
    assertThat(redisTemplate.opsForHash().get("drop:" + dropId, "closeAt")).isEqualTo("-1");
    assertThat(redisTemplate.opsForHash().get("drop:" + dropId, "remaining")).isEqualTo("9");
    assertThat(redisTemplate.opsForHash().get("drop:" + dropId + ":buyers", buyerId.toString()))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("워밍된 드롭들의 잔여를 배치로 조회하고 미워밍 드롭은 결과에서 제외한다")
  void findRemaining_returnsWarmedOnly() {
    // given
    UUID warmedA = UUID.randomUUID();
    UUID warmedB = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    warm(openState(warmedA, 10, null));
    warm(openState(warmedB, 3, null));

    // when
    Map<UUID, Long> remaining = adaptor.findRemaining(List.of(warmedA, warmedB, missing));

    // then
    assertThat(remaining).containsOnlyKeys(warmedA, warmedB);
    assertThat(remaining.get(warmedA)).isEqualTo(10);
    assertThat(remaining.get(warmedB)).isEqualTo(3);
  }

  @Test
  @DisplayName("빈 목록이면 빈 맵을 반환한다")
  void findRemaining_empty_returnsEmptyMap() {
    assertThat(adaptor.findRemaining(List.of())).isEmpty();
  }

  private void warm(DropCacheState state) {
    UUID owner = UUID.randomUUID();
    assertThat(recoveryAdaptor.beginRecovery(state.dropId(), owner, Duration.ofSeconds(30)))
        .isTrue();
    try {
      adaptor.warm(state, owner);
    } finally {
      recoveryAdaptor.completeRecovery(state.dropId(), owner);
    }
  }

  private DropCacheState openState(UUID dropId, long remaining, Integer limitPerUser) {
    Instant openAt = Instant.now().minusSeconds(60);
    return new DropCacheState(dropId, remaining, openAt, null, limitPerUser, Map.of());
  }

  private DropCacheState state(UUID dropId, long remaining, Map<UUID, Long> buyers) {
    return new DropCacheState(dropId, remaining, now().minusSeconds(60), null, null, buyers);
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private Instant now() {
    return Instant.now();
  }

  private StockCommandResult deduct(UUID dropId, UUID orderId, UUID buyerId, int quantity) {
    return adaptor.deduct(new StockMutation(dropId, orderId, buyerId, quantity));
  }

  private StockCommandResult rollback(UUID dropId, UUID orderId, UUID buyerId, int quantity) {
    return adaptor.rollback(new StockMutation(dropId, orderId, buyerId, quantity));
  }
}

package com.openat.drop.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.config.DropProperties;
import com.openat.drop.domain.model.StockCommandStatus;
import com.openat.drop.domain.repository.DropCacheState;
import com.openat.drop.domain.repository.StockCommandResult;
import com.openat.drop.domain.repository.StockMutation;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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
@DisplayName("드롭 캐시(Redis) 어댑터")
class DropCacheRedisAdaptorTest {

  @Container
  static final GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static LettuceConnectionFactory connectionFactory;
  static StringRedisTemplate redisTemplate;
  static DropCacheRedisAdaptor adaptor;

  @BeforeAll
  static void init() {
    connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    DropProperties properties =
        new DropProperties(
            Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(1));
    adaptor = new DropCacheRedisAdaptor(redisTemplate, properties);
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
    adaptor.warm(openState(dropId, 10, null));

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
    adaptor.warm(openState(dropId, 10, null));
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
    adaptor.warm(new DropCacheState(dropId, 10, openAt, null, null, Map.of()));

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
    adaptor.warm(new DropCacheState(dropId, 10, openAt, closeAt, null, Map.of()));

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
    adaptor.warm(openState(dropId, 10, 1));
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
    adaptor.warm(openState(dropId, 1, null));

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
    adaptor.warm(openState(dropId, 5, null));
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
  @DisplayName("동시 차감 요청에도 재고를 초과 판매하지 않는다")
  void deduct_concurrentRequests_neverOversells() throws InterruptedException {
    // given
    UUID dropId = UUID.randomUUID();
    int stock = 100;
    int requests = 300;
    adaptor.warm(openState(dropId, stock, null));
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
    adaptor.warm(openState(dropId, 10, null));
    deduct(dropId, inflightOrder, buyer, 1);

    // when
    adaptor.markClosed(dropId, now());

    // then
    StockCommandResult fresh = deduct(dropId, UUID.randomUUID(), UUID.randomUUID(), 1);
    assertThat(fresh.status()).isEqualTo(StockCommandStatus.CLOSED);
    StockCommandResult retry = deduct(dropId, inflightOrder, buyer, 1);
    assertThat(retry.status()).isEqualTo(StockCommandStatus.DUPLICATE);
  }

  private DropCacheState openState(UUID dropId, long remaining, Integer limitPerUser) {
    Instant openAt = Instant.now().minusSeconds(60);
    return new DropCacheState(dropId, remaining, openAt, null, limitPerUser, Map.of());
  }

  private Instant now() {
    return Instant.now();
  }

  private StockCommandResult deduct(UUID dropId, UUID orderId, UUID buyerId, int quantity) {
    return adaptor.deduct(new StockMutation(dropId, orderId, buyerId, quantity), now());
  }

  private StockCommandResult rollback(UUID dropId, UUID orderId, UUID buyerId, int quantity) {
    return adaptor.rollback(new StockMutation(dropId, orderId, buyerId, quantity));
  }
}

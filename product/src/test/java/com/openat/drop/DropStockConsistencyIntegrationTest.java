package com.openat.drop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.openat.common.exception.BusinessException;
import com.openat.drop.application.dto.DropStockCommand;
import com.openat.drop.application.port.DropStockMetricsPort;
import com.openat.drop.application.service.DropCacheWarmer;
import com.openat.drop.application.service.DropCloseService;
import com.openat.drop.application.service.DropCommandService;
import com.openat.drop.application.service.DropStockService;
import com.openat.drop.application.service.StockHistoryRecorder;
import com.openat.drop.domain.error.DropErrorCode;
import com.openat.drop.domain.event.DropClosedEvent;
import com.openat.drop.domain.event.DropDeletedEvent;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockHistoryRepository;
import com.openat.drop.domain.repository.StockMutation;
import com.openat.drop.infrastructure.schedule.DropBootstrapRunner;
import com.openat.product.domain.event.ProductDeletedEvent;
import com.openat.product.domain.model.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.profiles.active=test",
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never",
      "spring.kafka.bootstrap-servers=localhost:9092",
      "spring.kafka.listener.auto-startup=false",
      "product.image.s3.bucket=test-image-bucket",
      "product.image.s3.staging-prefix=images/staging/",
      "product.image.s3.final-prefix=images/final/",
      "product.image.s3.endpoint-override=http://localhost:9000",
      "product.image.s3.access-key=test-access-key",
      "product.image.s3.secret-key=test-secret-key"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DropStockConsistencyIntegrationTest.FailureConfig.class)
@DisplayName("드롭 재고 정합성 통합")
class DropStockConsistencyIntegrationTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
  }

  @Autowired private DropCacheWarmer dropCacheWarmer;
  @Autowired private DropCloseService dropCloseService;
  @Autowired private DropCommandService dropCommandService;
  @Autowired private DropStockService dropStockService;
  @MockitoSpyBean private StockHistoryRecorder stockHistoryRecorder;
  @Autowired private DropRepository dropRepository;
  @Autowired private StockHistoryRepository stockHistoryRepository;
  @Autowired private DropBootstrapRunner dropBootstrapRunner;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private LifecycleCommitFailure lifecycleCommitFailure;
  @MockitoSpyBean private DropCacheRepository dropCacheRepository;
  @MockitoSpyBean private DropStockMetricsPort dropStockMetrics;
  @PersistenceContext private EntityManager entityManager;

  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    reset(dropCacheRepository, stockHistoryRecorder, dropStockMetrics);
    lifecycleCommitFailure.clear();
    redisTemplate.execute(
        (RedisCallback<Void>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  @AfterEach
  void tearDown() {
    if (executor != null) {
      executor.shutdownNow();
    }
    reset(dropCacheRepository, stockHistoryRecorder, dropStockMetrics);
  }

  @Test
  @DisplayName("Redis 종료 표시 실패는 DB CLOSE 커밋을 막는다")
  void close_cacheFailure_rollsBackDbTransition() {
    TestDrop target = persistDrop(10, Instant.now().minusSeconds(60), null);
    dropCacheWarmer.warm(target.dropId());
    willThrow(new IllegalStateException("redis unavailable"))
        .given(dropCacheRepository)
        .markClosed(target.dropId());

    assertThatThrownBy(() -> dropCloseService.close(target.dropId()))
        .isInstanceOf(RuntimeException.class);

    reset(dropCacheRepository);
    assertThat(findStatus(target.dropId())).isEqualTo(DropStatus.REGISTERED);
  }

  @Test
  @DisplayName("Redis 제거 실패는 오픈 전 soft delete 커밋을 막는다")
  void delete_cacheFailure_rollsBackSoftDelete() {
    TestDrop target = persistDrop(10, Instant.now().plusSeconds(3600), null);
    dropCacheWarmer.warm(target.dropId());
    willThrow(new IllegalStateException("redis unavailable"))
        .given(dropCacheRepository)
        .evictBeforeOpen(target.dropId());

    assertThatThrownBy(() -> dropCommandService.delete(target.dropId(), target.sellerId()))
        .isInstanceOf(RuntimeException.class);

    reset(dropCacheRepository);
    assertThat(findStatus(target.dropId())).isEqualTo(DropStatus.REGISTERED);
  }

  @Test
  @DisplayName("직접 삭제의 캐시 차단이 오픈 경계를 넘으면 차감은 보존하고 DB 삭제를 중단한다")
  void delete_crossesOpenBoundary_rejectsDeleteAtRedisFence() throws Exception {
    Instant openAt = Instant.now().plusMillis(800);
    TestDrop target = persistDrop(1, openAt, null);
    dropCacheWarmer.warm(target.dropId());

    assertOpenBoundaryDeleteRace(
        target,
        openAt,
        () -> dropCommandService.delete(target.dropId(), target.sellerId()));
  }

  @Test
  @DisplayName("상품 하향 삭제의 캐시 차단이 오픈 경계를 넘으면 차감은 보존하고 DB 삭제를 중단한다")
  void productDelete_crossesOpenBoundary_rejectsDeleteAtRedisFence() throws Exception {
    Instant openAt = Instant.now().plusMillis(800);
    TestDrop target = persistDrop(1, openAt, null);
    dropCacheWarmer.warm(target.dropId());

    assertOpenBoundaryDeleteRace(
        target,
        openAt,
        () ->
            dropCommandService.onProductDeleted(
                new ProductDeletedEvent(target.productId(), 1L, Instant.now())));
  }

  @Test
  @DisplayName("캐시 종료 표시 뒤 DB 커밋이 실패하면 원래 closeAt만 복구한다")
  void close_dbFailureAfterCacheFence_restoresRegisteredCache() {
    TestDrop target = persistDrop(3, Instant.now().minusSeconds(60), null);
    UUID buyerId = UUID.randomUUID();
    stockHistoryRecorder.record(
        new StockMutation(target.dropId(), UUID.randomUUID(), buyerId, 1), StockChangeType.DEDUCT);
    dropCacheWarmer.warm(target.dropId());
    lifecycleCommitFailure.failClose(target.dropId());

    assertThatThrownBy(() -> dropCloseService.close(target.dropId()))
        .isInstanceOf(RuntimeException.class);

    assertThat(findStatus(target.dropId())).isEqualTo(DropStatus.REGISTERED);
    assertThat(redisTemplate.opsForHash().get("drop:" + target.dropId(), "closeAt"))
        .isEqualTo("-1");
    assertThat(redisTemplate.opsForHash().get("drop:" + target.dropId(), "remaining"))
        .isEqualTo("2");
    assertThat(
            redisTemplate
                .opsForHash()
                .get("drop:" + target.dropId() + ":buyers", buyerId.toString()))
        .isEqualTo("1");
  }

  @Test
  @DisplayName("warm과 종료가 공유하는 drop 행 잠금은 lifecycle 변경을 직렬화한다")
  void close_waitsForDropLifecycleLock() throws Exception {
    TestDrop target = persistDrop(3, Instant.now().minusSeconds(60), null);
    dropCacheWarmer.warm(target.dropId());
    CountDownLatch lockAcquired = new CountDownLatch(1);
    CountDownLatch releaseLock = new CountDownLatch(1);
    executor = Executors.newFixedThreadPool(2);
    Future<?> warmSide =
        executor.submit(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      dropRepository.findByIdForUpdate(target.dropId()).orElseThrow();
                      lockAcquired.countDown();
                      await(releaseLock);
                    }));
    assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();
    Future<?> closeSide = executor.submit(() -> dropCloseService.close(target.dropId()));

    try {
      assertThatThrownBy(() -> closeSide.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
    } finally {
      releaseLock.countDown();
    }
    warmSide.get(5, TimeUnit.SECONDS);
    closeSide.get(5, TimeUnit.SECONDS);

    assertThat(findStatus(target.dropId())).isEqualTo(DropStatus.CLOSE);
  }

  @Test
  @DisplayName("기동 복구는 등록 드롭의 stale cache를 DB 원장 스냅샷으로 교체한다")
  void bootstrap_registeredDrop_replacesStaleCacheFromLedger() throws Exception {
    TestDrop target = persistDrop(10, Instant.now().minusSeconds(60), null);
    UUID buyerId = UUID.randomUUID();
    stockHistoryRecorder.record(
        new StockMutation(target.dropId(), UUID.randomUUID(), buyerId, 3), StockChangeType.DEDUCT);
    dropCacheWarmer.warm(target.dropId());
    dropCacheRepository.markClosed(target.dropId());
    redisTemplate
        .opsForHash()
        .put("drop:" + target.dropId() + ":buyers", UUID.randomUUID().toString(), "9");

    dropBootstrapRunner.run(new DefaultApplicationArguments());

    assertThat(redisTemplate.opsForHash().get("drop:" + target.dropId(), "remaining"))
        .isEqualTo("7");
    assertThat(redisTemplate.opsForHash().get("drop:" + target.dropId(), "closeAt"))
        .isEqualTo("-1");
    assertThat(redisTemplate.opsForHash().entries("drop:" + target.dropId() + ":buyers"))
        .containsOnly(java.util.Map.entry(buyerId.toString(), "3"));
  }

  @Test
  @DisplayName("완료 롤백 재시도와 실제 동시 차감은 원장 재고를 초과 판매하지 않는다")
  void completedRollbackWithConcurrentDeduct_neverOversellsOrRestoresGhostBuyer() throws Exception {
    TestDrop target = persistDrop(1, Instant.now().minusSeconds(60), null);
    UUID rolledBackOrderId = UUID.randomUUID();
    UUID rolledBackBuyerId = UUID.randomUUID();
    StockMutation completed =
        new StockMutation(target.dropId(), rolledBackOrderId, rolledBackBuyerId, 1);
    stockHistoryRecorder.record(completed, StockChangeType.DEDUCT);
    stockHistoryRecorder.record(completed, StockChangeType.ROLLBACK);
    dropCacheWarmer.warm(target.dropId());

    int attempts = 8;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    executor = Executors.newFixedThreadPool(attempts + 1);
    List<Future<?>> requests = new ArrayList<>();
    requests.add(
        executor.submit(
            () -> {
              await(start);
              Optional<Long> remaining =
                  dropStockService.rollback(
                      new DropStockCommand(
                          target.dropId(), rolledBackOrderId, rolledBackBuyerId, 1));
              assertThat(remaining).isPresent();
            }));
    for (int index = 0; index < attempts; index++) {
      UUID orderId = UUID.randomUUID();
      UUID buyerId = UUID.randomUUID();
      requests.add(
          executor.submit(
              () -> {
                await(start);
                try {
                  dropStockService.deduct(
                      new DropStockCommand(target.dropId(), orderId, buyerId, 1));
                  successCount.incrementAndGet();
                } catch (BusinessException rejected) {
                  assertThat(rejected.getErrorCode()).isEqualTo(DropErrorCode.SOLD_OUT);
                }
              }));
    }
    start.countDown();
    for (Future<?> request : requests) {
      request.get(10, TimeUnit.SECONDS);
    }

    assertThat(successCount).hasValue(1);
    assertThat(dropCacheRepository.findRemaining(List.of(target.dropId())).get(target.dropId()))
        .isZero();
    assertThat(redisTemplate.opsForHash().entries("drop:" + target.dropId() + ":buyers"))
        .hasSize(1)
        .doesNotContainKey(rolledBackBuyerId.toString());
    assertThat(stockHistoryRepository.sumQuantityDeltaByDropId(target.dropId())).isEqualTo(-1);
  }

  @Test
  @DisplayName("동시 최초 롤백은 DB 원장 잠금으로 직렬화되어 멱등 키 만료와 교차 차감에도 중복 복원하지 않는다")
  void concurrentFirstRollback_serializesThroughCommittedLedger() throws Exception {
    TestDrop target = persistDrop(1, Instant.now().minusSeconds(60), null);
    UUID rollbackOrderId = UUID.randomUUID();
    UUID rollbackBuyerId = UUID.randomUUID();
    StockMutation rollbackMutation =
        new StockMutation(target.dropId(), rollbackOrderId, rollbackBuyerId, 1);
    stockHistoryRecorder.record(rollbackMutation, StockChangeType.DEDUCT);
    dropCacheWarmer.warm(target.dropId());

    CountDownLatch firstRecordEntered = new CountDownLatch(1);
    CountDownLatch allowFirstRecord = new CountDownLatch(1);
    CountDownLatch firstMetricEntered = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    AtomicBoolean firstRollbackRecord = new AtomicBoolean(true);
    AtomicBoolean firstRollbackMetric = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              if (firstRollbackRecord.compareAndSet(true, false)) {
                firstRecordEntered.countDown();
                await(allowFirstRecord);
              }
              return invocation.callRealMethod();
            })
        .when(stockHistoryRecorder)
        .record(any(StockMutation.class), eq(StockChangeType.ROLLBACK));
    doAnswer(
            invocation -> {
              if (firstRollbackMetric.compareAndSet(true, false)) {
                firstMetricEntered.countDown();
                await(allowFirstCommit);
              }
              return invocation.callRealMethod();
            })
        .when(dropStockMetrics)
        .register(target.dropId());

    executor = Executors.newFixedThreadPool(3);
    DropStockCommand rollbackCommand =
        new DropStockCommand(target.dropId(), rollbackOrderId, rollbackBuyerId, 1);
    Future<Optional<Long>> first = executor.submit(() -> dropStockService.rollback(rollbackCommand));
    try {
      assertThat(firstRecordEntered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<Optional<Long>> second =
          executor.submit(() -> dropStockService.rollback(rollbackCommand));
      assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      allowFirstRecord.countDown();
      assertThat(firstMetricEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(redisTemplate.delete("order:" + rollbackOrderId + ":rollback")).isTrue();

      UUID interleavedOrderId = UUID.randomUUID();
      UUID interleavedBuyerId = UUID.randomUUID();
      assertThat(
              dropStockService.deduct(
                  new DropStockCommand(
                      target.dropId(), interleavedOrderId, interleavedBuyerId, 1)))
          .isZero();
      assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      allowFirstCommit.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).contains(1L);
      assertThat(second.get(5, TimeUnit.SECONDS)).contains(0L);
      assertThat(dropCacheRepository.findRemaining(List.of(target.dropId())).get(target.dropId()))
          .isZero();
      assertThat(redisTemplate.opsForHash().entries("drop:" + target.dropId() + ":buyers"))
          .containsOnly(java.util.Map.entry(interleavedBuyerId.toString(), "1"))
          .doesNotContainKey(rollbackBuyerId.toString());
      assertThat(stockHistoryRepository.sumQuantityDeltaByDropId(target.dropId())).isEqualTo(-1);
    } finally {
      allowFirstRecord.countDown();
      allowFirstCommit.countDown();
    }
  }

  @Test
  @DisplayName("종료 드롭의 상품 삭제는 drain cache를 보존해 in-flight 롤백을 받는다")
  void productDelete_closedDrop_preservesDrainRollback() {
    TestDrop target = persistDrop(2, Instant.now().minusSeconds(60), null);
    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    StockMutation deduction = new StockMutation(target.dropId(), orderId, buyerId, 1);
    stockHistoryRecorder.record(deduction, StockChangeType.DEDUCT);
    dropCacheWarmer.warm(target.dropId());
    dropCloseService.close(target.dropId());

    dropCommandService.onProductDeleted(
        new ProductDeletedEvent(target.productId(), 1L, Instant.now()));
    Optional<Long> remaining =
        dropStockService.rollback(new DropStockCommand(target.dropId(), orderId, buyerId, 1));

    assertThat(remaining).contains(2L);
    assertThat(stockHistoryRepository.findByOrderIdAndChangeType(orderId, StockChangeType.ROLLBACK))
        .isPresent();
  }

  private TestDrop persistDrop(int totalQuantity, Instant openAt, Instant closeAt) {
    return transactionTemplate.execute(
        status -> {
          UUID sellerId = UUID.randomUUID();
          Product product =
              Product.create().sellerId(sellerId).name("재고 정합성 테스트 상품").price(10_000L).build();
          entityManager.persist(product);
          Drop drop =
              Drop.schedule()
                  .product(product)
                  .dropPrice(9_000L)
                  .totalQuantity(totalQuantity)
                  .openAt(openAt)
                  .closeAt(closeAt)
                  .build();
          entityManager.persist(drop);
          entityManager.flush();
          return new TestDrop(product.getId(), drop.getId(), sellerId);
        });
  }

  private DropStatus findStatus(UUID dropId) {
    return transactionTemplate.execute(
        status -> dropRepository.findById(dropId).orElseThrow().getStatus());
  }

  private void assertOpenBoundaryDeleteRace(
      TestDrop target, Instant openAt, Runnable deletion) throws Exception {
    lifecycleCommitFailure.pauseDeleteBeforeCacheFence(target.dropId());
    executor = Executors.newFixedThreadPool(2);
    Future<?> deleteRequest = executor.submit(deletion);
    lifecycleCommitFailure.awaitDeleteBeforeCacheFence();

    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    try {
      waitUntil(openAt.plusMillis(200));
      assertThat(
              dropStockService.deduct(
                  new DropStockCommand(target.dropId(), orderId, buyerId, 1)))
          .isZero();
    } finally {
      lifecycleCommitFailure.releaseDelete();
    }

    assertThatThrownBy(() -> deleteRequest.get(5, TimeUnit.SECONDS))
        .hasRootCauseInstanceOf(BusinessException.class);
    assertThat(findStatus(target.dropId())).isEqualTo(DropStatus.REGISTERED);
    assertThat(dropCacheRepository.findRemaining(List.of(target.dropId())).get(target.dropId()))
        .isZero();
    assertThat(redisTemplate.opsForHash().entries("drop:" + target.dropId() + ":buyers"))
        .containsEntry(buyerId.toString(), "1");
    assertThat(stockHistoryRepository.findByOrderIdAndChangeType(orderId, StockChangeType.DEDUCT))
        .isPresent();
  }

  private void waitUntil(Instant target) throws InterruptedException {
    long delayMillis = target.toEpochMilli() - Instant.now().toEpochMilli();
    if (delayMillis > 0) {
      TimeUnit.MILLISECONDS.sleep(delayMillis);
    }
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test latch timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", interrupted);
    }
  }

  private record TestDrop(UUID productId, UUID dropId, UUID sellerId) {}

  @TestConfiguration
  static class FailureConfig {

    @Bean
    LifecycleCommitFailure lifecycleCommitFailure() {
      return new LifecycleCommitFailure();
    }
  }

  static class LifecycleCommitFailure {

    private volatile UUID closeDropId;
    private volatile UUID delayedDeleteDropId;
    private volatile CountDownLatch deleteBeforeCacheFence = new CountDownLatch(0);
    private volatile CountDownLatch releaseDelete = new CountDownLatch(0);

    void failClose(UUID dropId) {
      this.closeDropId = dropId;
    }

    void pauseDeleteBeforeCacheFence(UUID dropId) {
      delayedDeleteDropId = dropId;
      deleteBeforeCacheFence = new CountDownLatch(1);
      releaseDelete = new CountDownLatch(1);
    }

    void awaitDeleteBeforeCacheFence() {
      await(deleteBeforeCacheFence);
    }

    void releaseDelete() {
      releaseDelete.countDown();
    }

    void clear() {
      releaseDelete.countDown();
      closeDropId = null;
      delayedDeleteDropId = null;
      deleteBeforeCacheFence = new CountDownLatch(0);
      releaseDelete = new CountDownLatch(0);
    }

    @Order(-100)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void pauseBeforeCacheFence(DropDeletedEvent event) {
      if (event.dropId().equals(delayedDeleteDropId)) {
        deleteBeforeCacheFence.countDown();
        await(releaseDelete);
      }
    }

    @Order(100)
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void failAfterCacheFence(DropClosedEvent event) {
      if (event.dropId().equals(closeDropId)) {
        throw new IllegalStateException("simulated commit failure");
      }
    }

    private void await(CountDownLatch latch) {
      try {
        if (!latch.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test latch timed out");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("test interrupted", interrupted);
      }
    }
  }
}

package com.openat.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.support.lock.SearchProjectionReferenceLock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresSearchProjectionReferenceLock.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("검색 투영 참조 트랜잭션 잠금")
class PostgresSearchProjectionReferenceLockTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private SearchProjectionReferenceLock referenceLock;
  @Autowired private PlatformTransactionManager transactionManager;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("같은 참조의 스냅샷 읽기 잠금끼리는 서로 막지 않는다")
  void lockSnapshotReads_sameReference_doNotBlock() throws Exception {
    UUID sellerId = UUID.randomUUID();
    CountDownLatch firstAcquired = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    Future<?> first =
        executor.submit(
            () ->
                inTransaction(
                    () -> {
                      referenceLock.lockSellerForSnapshotRead(sellerId);
                      firstAcquired.countDown();
                      await(releaseFirst);
                    }));
    assertThat(firstAcquired.await(1, TimeUnit.SECONDS)).isTrue();

    Future<?> second =
        executor.submit(
            () ->
                inTransaction(
                    () -> referenceLock.lockSellerForSnapshotRead(sellerId)));

    second.get(1, TimeUnit.SECONDS);
    releaseFirst.countDown();
    first.get(1, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("같은 참조의 변경 잠금은 진행 중인 스냅샷 읽기가 끝날 때까지 기다린다")
  void lockReferenceWrite_sameReference_waitsForSnapshotRead() throws Exception {
    UUID sellerId = UUID.randomUUID();
    CountDownLatch readAcquired = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    CountDownLatch writeStarted = new CountDownLatch(1);

    Future<?> reader =
        executor.submit(
            () ->
                inTransaction(
                    () -> {
                      referenceLock.lockSellerForSnapshotRead(sellerId);
                      readAcquired.countDown();
                      await(releaseRead);
                    }));
    assertThat(readAcquired.await(1, TimeUnit.SECONDS)).isTrue();

    Future<?> writer =
        executor.submit(
            () -> {
              writeStarted.countDown();
              inTransaction(
                  () -> referenceLock.lockSellerForReferenceWrite(sellerId));
            });
    assertThat(writeStarted.await(1, TimeUnit.SECONDS)).isTrue();
    try {
      assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
    } finally {
      releaseRead.countDown();
    }

    reader.get(1, TimeUnit.SECONDS);
    writer.get(1, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("같은 식별자의 판매자와 카테고리는 서로 다른 잠금 공간을 사용한다")
  void lockReferences_differentNamespaces_doNotBlock() throws Exception {
    UUID referenceId = UUID.randomUUID();
    CountDownLatch sellerAcquired = new CountDownLatch(1);
    CountDownLatch releaseSeller = new CountDownLatch(1);

    Future<?> seller =
        executor.submit(
            () ->
                inTransaction(
                    () -> {
                      referenceLock.lockSellerForReferenceWrite(referenceId);
                      sellerAcquired.countDown();
                      await(releaseSeller);
                    }));
    assertThat(sellerAcquired.await(1, TimeUnit.SECONDS)).isTrue();

    Future<?> category =
        executor.submit(
            () ->
                inTransaction(
                    () -> referenceLock.lockCategoryForReferenceWrite(referenceId)));
    category.get(1, TimeUnit.SECONDS);

    releaseSeller.countDown();
    seller.get(1, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("트랜잭션 밖에서는 참조 잠금을 획득할 수 없다")
  void lockReference_withoutTransaction_rejects() {
    assertThatThrownBy(
            () -> referenceLock.lockSellerForSnapshotRead(UUID.randomUUID()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  private void inTransaction(Runnable action) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test latch timed out");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", exception);
    }
  }
}

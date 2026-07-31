package com.openat.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.category.domain.model.Category;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductSearchProjectionRefreshTargetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
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
@Import(ProductSearchProjectionRefreshTargetRepositoryAdaptor.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("상품 검색 투영 갱신 대상 영속성")
class ProductSearchProjectionRefreshTargetRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:16-alpine");

  @Autowired
  private ProductSearchProjectionRefreshTargetRepository repository;

  @Autowired private PlatformTransactionManager transactionManager;
  @PersistenceContext private EntityManager entityManager;

  @AfterEach
  void cleanUp() {
    inTransaction(
        () -> {
          entityManager
              .createNativeQuery(
                  "DELETE FROM product.product_search_projection_refresh_targets")
              .executeUpdate();
          entityManager
              .createNativeQuery("DELETE FROM product.product_images")
              .executeUpdate();
          entityManager
              .createNativeQuery("DELETE FROM product.products")
              .executeUpdate();
          entityManager
              .createNativeQuery("DELETE FROM product.categories")
              .executeUpdate();
          return null;
        });
  }

  @Test
  @DisplayName("판매자의 살아있는 상품만 중복 없이 갱신 대상으로 적재한다")
  void enqueueBySellerId_aliveProducts_deduplicatesTargets() {
    UUID sellerId = UUID.randomUUID();
    UUID otherSellerId = UUID.randomUUID();
    List<UUID> expected =
        inTransaction(
            () -> {
              Product first = persistProduct(sellerId, null, "첫 상품");
              Product second = persistProduct(sellerId, null, "둘째 상품");
              persistProduct(otherSellerId, null, "다른 판매자 상품");
              entityManager.flush();
              return List.of(first.getId(), second.getId());
            });

    inTransaction(() -> repository.enqueueBySellerId(sellerId));
    inTransaction(() -> repository.enqueueBySellerId(sellerId));
    List<UUID> firstBatch =
        inTransaction(
            () -> {
              List<UUID> claimed =
                  repository.findNextProductIdsForUpdate(1);
              repository.deleteAllByProductIds(claimed);
              return claimed;
            });
    List<UUID> secondBatch =
        inTransaction(() -> repository.findNextProductIdsForUpdate(10));

    assertThat(firstBatch).hasSize(1);
    assertThat(secondBatch).hasSize(1);
    assertThat(firstBatch)
        .doesNotContainAnyElementsOf(secondBatch);
    assertThat(
            List.of(firstBatch.getFirst(), secondBatch.getFirst()))
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  @DisplayName("카테고리 삭제 전에 대상을 적재하면 FK가 null로 바뀐 뒤에도 갱신할 수 있다")
  void enqueueByCategoryId_beforeDelete_preservesTargetsAfterSetNull() {
    CategoryTarget target =
        inTransaction(
            () -> {
              Category category = Category.create().name("삭제 대상").build();
              entityManager.persist(category);
              Product product =
                  persistProduct(
                      UUID.randomUUID(), category, "카테고리 상품");
              entityManager.flush();
              repository.enqueueByCategoryId(category.getId());
              entityManager.clear();
              entityManager
                  .createNativeQuery(
                      "DELETE FROM product.categories WHERE id = :categoryId")
                  .setParameter("categoryId", category.getId())
                  .executeUpdate();
              return new CategoryTarget(product.getId());
            });

    Product reloaded =
        inTransaction(
            () -> entityManager.find(Product.class, target.productId()));
    List<UUID> claimed =
        inTransaction(() -> repository.findNextProductIdsForUpdate(10));

    assertThat(reloaded.getCategory()).isNull();
    assertThat(claimed).containsExactly(target.productId());
  }

  @Test
  @DisplayName("한 인스턴스가 잠근 대상은 다른 인스턴스가 건너뛰고 다음 대상을 claim한다")
  void findNextProductIdsForUpdate_lockedTarget_skipsToNextTarget()
      throws Exception {
    UUID sellerId = UUID.randomUUID();
    List<UUID> productIds =
        inTransaction(
            () -> {
              Product first = persistProduct(sellerId, null, "첫 상품");
              Product second = persistProduct(sellerId, null, "둘째 상품");
              entityManager.flush();
              repository.enqueueBySellerId(sellerId);
              return List.of(first.getId(), second.getId());
            });
    CountDownLatch firstClaimed = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<List<UUID>> first =
          executor.submit(
              () ->
                  inTransaction(
                      () -> {
                        List<UUID> claimed =
                            repository.findNextProductIdsForUpdate(1);
                        firstClaimed.countDown();
                        await(releaseFirst);
                        return claimed;
                      }));
      assertThat(firstClaimed.await(1, TimeUnit.SECONDS)).isTrue();

      List<UUID> second =
          executor
              .submit(
                  () ->
                      inTransaction(
                          () ->
                              repository.findNextProductIdsForUpdate(10)))
              .get(1, TimeUnit.SECONDS);

      releaseFirst.countDown();
      List<UUID> firstResult = first.get(1, TimeUnit.SECONDS);
      assertThat(firstResult).hasSize(1);
      assertThat(second).hasSize(1);
      assertThat(firstResult).doesNotContainAnyElementsOf(second);
      assertThat(firstResult).containsAnyElementsOf(productIds);
      assertThat(second).containsAnyElementsOf(productIds);
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("처리 중 같은 상품이 다시 적재되면 처리 완료 뒤 새 대상으로 남는다")
  void enqueueWhileClaimed_afterDelete_insertsFollowUpTarget()
      throws Exception {
    UUID sellerId = UUID.randomUUID();
    UUID productId =
        inTransaction(
            () -> {
              Product product =
                  persistProduct(sellerId, null, "재적재 상품");
              entityManager.flush();
              repository.enqueueBySellerId(sellerId);
              return product.getId();
            });
    CountDownLatch targetClaimed = new CountDownLatch(1);
    CountDownLatch allowDelete = new CountDownLatch(1);
    CountDownLatch enqueueStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> processor =
          executor.submit(
              () ->
                  inTransaction(
                      () -> {
                        List<UUID> claimed =
                            repository.findNextProductIdsForUpdate(1);
                        targetClaimed.countDown();
                        await(allowDelete);
                        repository.deleteAllByProductIds(claimed);
                        return null;
                      }));
      assertThat(targetClaimed.await(1, TimeUnit.SECONDS)).isTrue();

      Future<Integer> enqueuer =
          executor.submit(
              () -> {
                enqueueStarted.countDown();
                return inTransaction(
                    () -> repository.enqueueBySellerId(sellerId));
              });
      assertThat(enqueueStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> enqueuer.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      allowDelete.countDown();
      processor.get(1, TimeUnit.SECONDS);
      assertThat(enqueuer.get(1, TimeUnit.SECONDS)).isEqualTo(1);
      List<UUID> followUp =
          inTransaction(() -> repository.findNextProductIdsForUpdate(10));
      assertThat(followUp).containsExactly(productId);
    } finally {
      allowDelete.countDown();
      executor.shutdownNow();
    }
  }

  private Product persistProduct(
      UUID sellerId, Category category, String name) {
    Product product =
        Product.create()
            .sellerId(sellerId)
            .name(name)
            .category(category)
            .price(10_000L)
            .build();
    entityManager.persist(product);
    return product;
  }

  private <T> T inTransaction(Supplier<T> action) {
    return new TransactionTemplate(transactionManager)
        .execute(status -> action.get());
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

  private record CategoryTarget(UUID productId) {}
}

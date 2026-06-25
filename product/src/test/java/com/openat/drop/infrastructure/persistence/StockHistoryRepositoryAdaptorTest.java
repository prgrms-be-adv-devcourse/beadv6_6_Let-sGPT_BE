package com.openat.drop.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.model.StockHistory;
import com.openat.drop.domain.repository.BuyerPurchase;
import com.openat.drop.domain.repository.StockHistoryRepository;
import com.openat.product.domain.model.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(StockHistoryRepositoryAdaptor.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("재고 이력 영속성")
class StockHistoryRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private StockHistoryRepository stockHistoryRepository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("같은 주문·변경유형으로 두 번 기록하면 멱등 유니크 제약을 위반한다")
  void save_duplicateOrderAndChangeType_violatesUniqueConstraint() {
    // given
    Drop drop = persistDrop();
    UUID orderId = UUID.randomUUID();
    UUID buyerId = UUID.randomUUID();
    stockHistoryRepository.save(deduct(drop, orderId, buyerId, 1));

    // when & then
    assertThatThrownBy(
            () -> {
              stockHistoryRepository.save(deduct(drop, orderId, buyerId, 1));
              entityManager.flush();
            })
        .isInstanceOf(ConstraintViolationException.class);
  }

  @Test
  @DisplayName("drop의 수량 증감 합을 집계한다 (DEDUCT 음수·ROLLBACK 양수)")
  void sumQuantityDeltaByDropId_aggregatesSignedDelta() {
    // given
    Drop drop = persistDrop();
    UUID buyerId = UUID.randomUUID();
    stockHistoryRepository.save(deduct(drop, UUID.randomUUID(), buyerId, 5));
    stockHistoryRepository.save(deduct(drop, UUID.randomUUID(), buyerId, 3));
    stockHistoryRepository.save(rollback(drop, UUID.randomUUID(), buyerId, 5));
    entityManager.flush();
    entityManager.clear();

    // when
    long sum = stockHistoryRepository.sumQuantityDeltaByDropId(drop.getId());

    // then
    assertThat(sum).isEqualTo(-3);
  }

  @Test
  @DisplayName("이력이 없는 drop의 증감 합은 0이다")
  void sumQuantityDeltaByDropId_noHistory_returnsZero() {
    // given
    Drop drop = persistDrop();

    // when & then
    assertThat(stockHistoryRepository.sumQuantityDeltaByDropId(drop.getId())).isZero();
  }

  @Test
  @DisplayName("구매자별 순구매 수량을 집계한다 (DEDUCT - ROLLBACK)")
  void sumNetQuantityByBuyer_aggregatesPerBuyer() {
    // given
    Drop drop = persistDrop();
    UUID buyerA = UUID.randomUUID();
    UUID buyerB = UUID.randomUUID();
    stockHistoryRepository.save(deduct(drop, UUID.randomUUID(), buyerA, 5));
    stockHistoryRepository.save(rollback(drop, UUID.randomUUID(), buyerA, 2));
    stockHistoryRepository.save(deduct(drop, UUID.randomUUID(), buyerB, 1));
    entityManager.flush();
    entityManager.clear();

    // when
    List<BuyerPurchase> purchases = stockHistoryRepository.sumNetQuantityByBuyer(drop.getId());

    // then
    assertThat(purchases)
        .extracting(BuyerPurchase::buyerId, BuyerPurchase::quantity)
        .containsExactlyInAnyOrder(tuple(buyerA, 3L), tuple(buyerB, 1L));
  }

  private Drop persistDrop() {
    Product product =
        Product.create().sellerId(UUID.randomUUID()).name("한정판 굿즈").price(10_000L).build();
    entityManager.persist(product);
    Drop drop =
        Drop.schedule()
            .product(product)
            .dropPrice(10_000L)
            .totalQuantity(100)
            .openAt(Instant.parse("2026-07-01T00:00:00Z"))
            .build();
    entityManager.persist(drop);
    return drop;
  }

  private StockHistory deduct(Drop drop, UUID orderId, UUID buyerId, int quantity) {
    return StockHistory.record()
        .dropId(drop.getId())
        .orderId(orderId)
        .buyerId(buyerId)
        .changeType(StockChangeType.DEDUCT)
        .quantity(quantity)
        .build();
  }

  private StockHistory rollback(Drop drop, UUID orderId, UUID buyerId, int quantity) {
    return StockHistory.record()
        .dropId(drop.getId())
        .orderId(orderId)
        .buyerId(buyerId)
        .changeType(StockChangeType.ROLLBACK)
        .quantity(quantity)
        .build();
  }
}

package com.openat.drop.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.config.QueryDslConfig;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.DropSearchCondition;
import com.openat.product.domain.model.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({DropRepositoryAdaptor.class, QueryDslConfig.class})
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("드롭 영속성")
class DropRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private DropRepository dropRepository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("저장하면 UUIDv7 id·생성 시각·초기 상태(REGISTERED)가 채워진다")
  void save_generatesIdAndRegisteredStatus() {
    // given
    Product product = persistProduct();
    Drop drop =
        Drop.schedule()
            .product(product)
            .dropPrice(10_000L)
            .totalQuantity(100)
            .limitPerUser(2)
            .openAt(Instant.parse("2026-07-01T00:00:00Z"))
            .closeAt(null)
            .build();

    // when
    Drop saved = dropRepository.save(drop);
    entityManager.flush();

    // then
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getStatus()).isEqualTo(DropStatus.REGISTERED);
  }

  @Test
  @DisplayName("findById로 조회한 드롭에서 상품을 로딩할 수 있다")
  void findById_loadsProduct() {
    // given
    Product product = persistProduct();
    Drop saved =
        dropRepository.save(
            Drop.schedule()
                .product(product)
                .dropPrice(10_000L)
                .totalQuantity(100)
                .openAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build());
    entityManager.flush();
    entityManager.clear();

    // when
    Drop found = dropRepository.findById(saved.getId()).orElseThrow();

    // then
    assertThat(found.getProduct().getId()).isEqualTo(product.getId());
  }

  @Test
  @DisplayName("findWithProductById는 상품을 fetch join해 productId·sellerId를 함께 로딩한다")
  void findWithProductById_fetchesProduct() {
    // given
    UUID sellerId = UUID.randomUUID();
    Product product = persistProduct(sellerId);
    Drop saved =
        dropRepository.save(
            Drop.schedule()
                .product(product)
                .dropPrice(219_000L)
                .totalQuantity(100)
                .openAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build());
    entityManager.flush();
    entityManager.clear();

    // when
    Drop found = dropRepository.findWithProductById(saved.getId()).orElseThrow();

    // then
    assertThat(found.getProduct().getId()).isEqualTo(product.getId());
    assertThat(found.getProduct().getSellerId()).isEqualTo(sellerId);
    assertThat(found.getDropPrice()).isEqualTo(219_000L);
  }

  @Test
  @DisplayName("없는 id면 findWithProductById는 빈 Optional을 반환한다")
  void findWithProductById_missing_returnsEmpty() {
    // when & then
    assertThat(dropRepository.findWithProductById(UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("삭제하면 soft delete되어 findById로 조회되지 않는다")
  void delete_softDeletesAndHidesFromFindById() {
    // given
    Product product = persistProduct();
    Drop saved =
        dropRepository.save(
            Drop.schedule()
                .product(product)
                .dropPrice(10_000L)
                .totalQuantity(100)
                .openAt(Instant.parse("2026-07-01T00:00:00Z"))
                .build());
    entityManager.flush();
    UUID id = saved.getId();

    // when
    dropRepository.delete(saved);
    entityManager.flush();
    entityManager.clear();

    // then
    assertThat(dropRepository.findById(id)).isEmpty();
  }

  @Test
  @DisplayName("상품 id로 그 상품의 드롭을 모두 조회한다")
  void findAllByProductId_returnsDropsOfProduct() {
    // given
    Product product = persistProduct();
    dropRepository.save(dropOf(product));
    dropRepository.save(dropOf(product));
    dropRepository.save(dropOf(persistProduct()));
    entityManager.flush();
    entityManager.clear();

    // when
    List<Drop> drops = dropRepository.findAllByProductId(product.getId());

    // then
    assertThat(drops).hasSize(2);
  }

  @Test
  @DisplayName("findAllByStatus는 상품이 soft delete된 고아 드롭을 제외하고 반환한다")
  void findAllByStatus_productSoftDeleted_excludesOrphanDrop() {
    // given
    Product liveProduct = persistProduct();
    Product deletedProduct = persistProduct();
    Drop liveDrop = dropRepository.save(dropOf(liveProduct));
    dropRepository.save(dropOf(deletedProduct));
    entityManager.flush();
    softDeleteProduct(deletedProduct.getId());
    entityManager.clear();

    // when
    List<Drop> drops = dropRepository.findAllByStatus(DropStatus.REGISTERED);

    // then
    assertThat(drops).extracting(Drop::getId).containsExactly(liveDrop.getId());
  }

  @Nested
  @DisplayName("검색")
  class Search {

    @Test
    @DisplayName("status=OPEN이면 오픈됐고 종료되지 않은 드롭만 반환한다")
    void search_statusOpen_returnsLiveDrops() {
      // given
      Instant now = Instant.now();
      Product product = persistProduct();
      persistDrop(product, now.minusSeconds(60), null);
      persistDrop(product, now.plusSeconds(3600), null);
      entityManager.flush();
      entityManager.clear();

      // when
      Page<Drop> result =
          dropRepository.search(
              new DropSearchCondition(DropStatus.OPEN, null, null, null),
              now,
              PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getOpenAt()).isBefore(now);
    }

    @Test
    @DisplayName("status=REGISTERED이면 오픈 전 드롭만 반환한다")
    void search_statusRegistered_returnsUpcoming() {
      // given
      Instant now = Instant.now();
      Product product = persistProduct();
      persistDrop(product, now.minusSeconds(60), null);
      Drop upcoming = persistDrop(product, now.plusSeconds(3600), null);
      entityManager.flush();
      entityManager.clear();

      // when
      Page<Drop> result =
          dropRepository.search(
              new DropSearchCondition(DropStatus.REGISTERED, null, null, null),
              now,
              PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getId()).isEqualTo(upcoming.getId());
    }

    @Test
    @DisplayName("status=CLOSE이면 종료(CLOSE)된 드롭을 반환한다")
    void search_statusClose_returnsClosed() {
      // given
      Instant now = Instant.now();
      Product product = persistProduct();
      persistDrop(product, now.minusSeconds(60), null);
      Drop closed = persistDrop(product, now.minusSeconds(60), null);
      closed.close();
      entityManager.flush();
      entityManager.clear();

      // when
      Page<Drop> result =
          dropRepository.search(
              new DropSearchCondition(DropStatus.CLOSE, null, null, null),
              now,
              PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getId()).isEqualTo(closed.getId());
    }

    @Test
    @DisplayName("sellerId로 필터하면 그 판매자의 상품 드롭만 반환한다")
    void search_bySellerId_returnsOwnDrops() {
      // given
      Instant now = Instant.now();
      UUID sellerId = UUID.randomUUID();
      Product owned = persistProduct(sellerId);
      Product others = persistProduct(UUID.randomUUID());
      persistDrop(owned, now.minusSeconds(60), null);
      persistDrop(others, now.minusSeconds(60), null);
      entityManager.flush();
      entityManager.clear();

      // when
      Page<Drop> result =
          dropRepository.search(
              new DropSearchCondition(null, null, null, sellerId), now, PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getProduct().getSellerId()).isEqualTo(sellerId);
    }
  }

  private Drop dropOf(Product product) {
    return Drop.schedule()
        .product(product)
        .dropPrice(10_000L)
        .totalQuantity(100)
        .openAt(Instant.parse("2026-07-01T00:00:00Z"))
        .build();
  }

  private void softDeleteProduct(UUID productId) {
    entityManager
        .createNativeQuery("update product.products set deleted_at = now() where id = :id")
        .setParameter("id", productId)
        .executeUpdate();
  }

  private Drop persistDrop(Product product, Instant openAt, Instant closeAt) {
    return dropRepository.save(
        Drop.schedule()
            .product(product)
            .dropPrice(10_000L)
            .totalQuantity(100)
            .openAt(openAt)
            .closeAt(closeAt)
            .build());
  }

  private Product persistProduct() {
    return persistProduct(UUID.randomUUID());
  }

  private Product persistProduct(UUID sellerId) {
    Product product = Product.create().sellerId(sellerId).name("한정판 굿즈").price(10_000L).build();
    entityManager.persist(product);
    return product;
  }
}

package com.openat.drop.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.product.domain.model.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
@Import(DropRepositoryAdaptor.class)
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

  private Drop dropOf(Product product) {
    return Drop.schedule()
        .product(product)
        .dropPrice(10_000L)
        .totalQuantity(100)
        .openAt(Instant.parse("2026-07-01T00:00:00Z"))
        .build();
  }

  private Product persistProduct() {
    Product product =
        Product.create().sellerId(UUID.randomUUID()).name("한정판 굿즈").price(10_000L).build();
    entityManager.persist(product);
    return product;
  }
}

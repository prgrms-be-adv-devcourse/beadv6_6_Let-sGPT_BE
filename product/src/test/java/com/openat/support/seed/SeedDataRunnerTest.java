package com.openat.support.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import com.openat.category.infrastructure.persistence.CategoryRepositoryAdaptor;
import com.openat.config.QueryDslConfig;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockHistoryRepository;
import com.openat.drop.infrastructure.persistence.DropRepositoryAdaptor;
import com.openat.drop.infrastructure.persistence.StockHistoryRepositoryAdaptor;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.infrastructure.persistence.ProductRepositoryAdaptor;
import com.openat.seller.application.service.SellerStoreCommandService;
import com.openat.seller.application.usecase.SellerStoreCommandUseCase;
import com.openat.seller.infrastructure.persistence.SellerStoreRepositoryAdaptor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
@Import({
  CategoryRepositoryAdaptor.class,
  ProductRepositoryAdaptor.class,
  DropRepositoryAdaptor.class,
  StockHistoryRepositoryAdaptor.class,
  SellerStoreRepositoryAdaptor.class,
  SellerStoreCommandService.class,
  QueryDslConfig.class
})
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("데모 시드 러너")
class SeedDataRunnerTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private CategoryRepository categoryRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private DropRepository dropRepository;
  @Autowired private StockHistoryRepository stockHistoryRepository;
  @Autowired private SellerStoreCommandUseCase sellerStoreCommandUseCase;
  @PersistenceContext private EntityManager entityManager;

  private SeedDataRunner runner;

  @BeforeEach
  void setUp() {
    runner =
        new SeedDataRunner(
            categoryRepository,
            productRepository,
            dropRepository,
            stockHistoryRepository,
            sellerStoreCommandUseCase);
    for (String name : List.of("의류", "액세서리", "문구", "전자기기", "피규어", "기타")) {
      entityManager.persist(Category.create().name(name).build());
    }
    entityManager.flush();
  }

  @Test
  @DisplayName("상품 16·드롭 10·오픈 구간 재고이력 6건을 시드한다")
  void run_seedsCatalog() {
    // when
    runner.run(null);
    entityManager.flush();
    entityManager.clear();

    // then
    assertThat(count("Product")).isEqualTo(16);
    assertThat(count("Drop")).isEqualTo(10);
    assertThat(count("StockHistory")).isEqualTo(6);
    assertThat(count("SellerStore")).isEqualTo(1);
    // 오픈 구간 잔여 차감 합: (100-37)+(50-8)+(200-152)+(150-64) + (60-0)+(90-0) = 389
    assertThat(totalQuantityDelta()).isEqualTo(-389);
  }

  @Test
  @DisplayName("이미 시드된 상태면 다시 실행해도 중복 삽입하지 않는다")
  void run_idempotent() {
    // when
    runner.run(null);
    runner.run(null);
    entityManager.flush();
    entityManager.clear();

    // then
    assertThat(count("Product")).isEqualTo(16);
    assertThat(count("Drop")).isEqualTo(10);
    assertThat(count("StockHistory")).isEqualTo(6);
  }

  @Test
  @DisplayName("상품이 이미 있어 카탈로그 시드를 건너뛰어도 데모 스토어 투영은 남긴다")
  void run_catalogAlreadySeededWithoutStore_projectsDemoStore() {
    // given
    entityManager.persist(
        Product.create()
            .sellerId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            .name("기존 상품")
            .price(10_000L)
            .build());
    entityManager.flush();

    // when
    runner.run(null);
    entityManager.flush();
    entityManager.clear();

    // then
    assertThat(count("SellerStore")).isEqualTo(1);
    assertThat(count("Product")).isEqualTo(1);
  }

  private long count(String entity) {
    return entityManager
        .createQuery("select count(e) from " + entity + " e", Long.class)
        .getSingleResult();
  }

  private long totalQuantityDelta() {
    return entityManager
        .createQuery("select coalesce(sum(s.quantityDelta), 0) from StockHistory s", Long.class)
        .getSingleResult();
  }
}

package com.openat.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.category.domain.model.Category;
import com.openat.config.QueryDslConfig;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchCondition;
import com.openat.product.domain.repository.ProductTombstone;
import com.openat.product.fixture.ProductFixture;
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
@Import({ProductRepositoryAdaptor.class, QueryDslConfig.class})
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("상품 영속성")
class ProductRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private ProductRepository productRepository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("저장하면 UUIDv7 id와 생성 시각이 채워진다")
  void save_generatesIdAndCreatedAt() {
    // given
    Product product =
        Product.create().sellerId(UUID.randomUUID()).name("한정판 스니커즈").price(219_000L).build();

    // when
    Product saved = productRepository.save(product);
    entityManager.flush();

    // then
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("findById로 조회한 상품에서 카테고리를 로딩할 수 있다")
  void findById_loadsCategory() {
    // given
    Category clothes = Category.create().name("테스트의류").build();
    entityManager.persist(clothes);
    Product saved =
        productRepository.save(
            Product.create()
                .sellerId(UUID.randomUUID())
                .name("한정판 스니커즈")
                .category(clothes)
                .price(219_000L)
                .build());
    entityManager.flush();
    entityManager.clear();

    // when
    Product found = productRepository.findById(saved.getId()).orElseThrow();

    // then
    assertThat(found.getCategory().getName()).isEqualTo("테스트의류");
  }

  @Test
  @DisplayName("참조 중인 카테고리가 삭제되면 상품의 카테고리는 null로 전환된다")
  void categoryDeleted_setsProductCategoryNull() {
    // given
    Category category = Category.create().name("테스트의류").build();
    entityManager.persist(category);
    Product product =
        Product.create()
            .sellerId(UUID.randomUUID())
            .name("한정판 스니커즈")
            .category(category)
            .price(219_000L)
            .build();
    UUID categoryId = category.getId();
    UUID productId = productRepository.save(product).getId();
    entityManager.flush();
    entityManager.clear();

    // when: DB에서 카테고리를 직접 삭제해 FK(ON DELETE SET NULL) 동작을 검증한다
    entityManager
        .createNativeQuery("DELETE FROM product.categories WHERE id = :id")
        .setParameter("id", categoryId)
        .executeUpdate();

    // then
    Product reloaded = entityManager.find(Product.class, productId);
    assertThat(reloaded.getCategory()).isNull();
  }

  @Test
  @DisplayName("삭제하면 soft delete되어 더 이상 목록에 조회되지 않는다")
  void delete_softDeletesAndExcludesFromList() {
    // given
    Product product = productRepository.save(ProductFixture.uncategorized(UUID.randomUUID()));
    entityManager.flush();

    // when
    productRepository.delete(product);
    entityManager.flush();
    entityManager.clear();

    // then
    Page<Product> result =
        productRepository.search(
            new ProductSearchCondition(null, null, null), PageRequest.of(0, 10));
    assertThat(result.getTotalElements()).isZero();
  }

  @Nested
  @DisplayName("검색")
  class Search {

    @Test
    @DisplayName("카테고리로 필터하면 해당 카테고리 상품만 카테고리명과 함께 반환한다")
    void search_byCategory_returnsOnlyMatchingWithCategoryName() {
      // given
      Category clothes = Category.create().name("의류검색").build();
      Category figure = Category.create().name("피규어검색").build();
      entityManager.persist(clothes);
      entityManager.persist(figure);
      UUID sellerId = UUID.randomUUID();
      productRepository.save(
          Product.create()
              .sellerId(sellerId)
              .name("스니커즈")
              .category(clothes)
              .price(200_000L)
              .build());
      productRepository.save(
          Product.create().sellerId(sellerId).name("피규어").category(figure).price(50_000L).build());
      entityManager.flush();
      entityManager.clear();

      // when
      ProductSearchCondition condition = new ProductSearchCondition(clothes.getId(), null, null);
      Page<Product> result = productRepository.search(condition, PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getName()).isEqualTo("스니커즈");
      assertThat(result.getContent().get(0).getCategory().getName()).isEqualTo("의류검색");
    }

    @Test
    @DisplayName("키워드로 필터하면 상품명에 키워드가 포함된 상품만 반환한다")
    void search_byKeyword_returnsNameContaining() {
      // given
      UUID sellerId = UUID.randomUUID();
      productRepository.save(
          Product.create().sellerId(sellerId).name("한정판 스니커즈").price(200_000L).build());
      productRepository.save(
          Product.create().sellerId(sellerId).name("일반 모자").price(30_000L).build());
      entityManager.flush();
      entityManager.clear();

      // when
      ProductSearchCondition condition = new ProductSearchCondition(null, "스니커즈", null);
      Page<Product> result = productRepository.search(condition, PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getName()).isEqualTo("한정판 스니커즈");
    }

    @Test
    @DisplayName("sellerId로 필터하면 그 판매자의 상품만 반환한다")
    void search_bySellerId_returnsOwnProducts() {
      // given
      UUID sellerId = UUID.randomUUID();
      productRepository.save(
          Product.create().sellerId(sellerId).name("내 상품").price(1_000L).build());
      productRepository.save(
          Product.create().sellerId(UUID.randomUUID()).name("남 상품").price(1_000L).build());
      entityManager.flush();
      entityManager.clear();

      // when
      ProductSearchCondition condition = new ProductSearchCondition(null, null, sellerId);
      Page<Product> result = productRepository.search(condition, PageRequest.of(0, 10));

      // then
      assertThat(result.getContent()).hasSize(1);
      assertThat(result.getContent().get(0).getSellerId()).isEqualTo(sellerId);
    }

    @Test
    @DisplayName("빈 조건이면 전체를 페이징해 반환한다")
    void search_emptyCondition_returnsAllPaged() {
      // given
      UUID sellerId = UUID.randomUUID();
      for (int i = 0; i < 3; i++) {
        productRepository.save(
            Product.create().sellerId(sellerId).name("상품" + i).price(1_000L).build());
      }
      entityManager.flush();
      entityManager.clear();

      // when
      Page<Product> result =
          productRepository.search(
              new ProductSearchCondition(null, null, null), PageRequest.of(0, 2));

      // then
      assertThat(result.getTotalElements()).isEqualTo(3);
      assertThat(result.getContent()).hasSize(2);
      assertThat(result.getTotalPages()).isEqualTo(2);
    }
  }

  @Nested
  @DisplayName("변경 피드(증분 동기화)")
  class ChangeFeed {

    @Test
    @DisplayName("수정 시각이 기준 이후인 살아있는 상품만 반환하고 삭제 상품은 제외한다")
    void searchChangedAliveSince_returnsChangedAlive_excludesDeleted() {
      // given
      Instant boundary = Instant.parse("2026-01-01T00:00:00Z");
      UUID sellerId = UUID.randomUUID();
      Product created =
          productRepository.save(
              Product.create().sellerId(sellerId).name("생성됨").price(1_000L).build());
      Product reupdated =
          productRepository.save(
              Product.create().sellerId(sellerId).name("수정됨").price(1_000L).build());
      Product old =
          productRepository.save(
              Product.create().sellerId(sellerId).name("옛날").price(1_000L).build());
      Product deleted =
          productRepository.save(
              Product.create().sellerId(sellerId).name("삭제됨").price(1_000L).build());
      entityManager.flush();
      setChangeTimestamps(
          created.getId(),
          Instant.parse("2026-06-02T00:00:00Z"),
          Instant.parse("2026-06-02T00:00:00Z"));
      setChangeTimestamps(
          reupdated.getId(),
          Instant.parse("2020-01-01T00:00:00Z"),
          Instant.parse("2026-06-03T00:00:00Z"));
      setChangeTimestamps(
          old.getId(),
          Instant.parse("2020-01-01T00:00:00Z"),
          Instant.parse("2020-01-01T00:00:00Z"));
      productRepository.delete(deleted);
      entityManager.flush();
      entityManager.clear();

      // when
      List<Product> changed = productRepository.searchChangedAliveSince(boundary);

      // then
      assertThat(changed)
          .extracting(Product::getId)
          .containsExactlyInAnyOrder(created.getId(), reupdated.getId());
    }

    @Test
    @DisplayName("삭제 시각이 기준 이후인 소프트삭제 상품의 id와 삭제 시각을 반환한다")
    void searchTombstonesSince_returnsSoftDeletedRows_afterBoundary() {
      // given
      UUID sellerId = UUID.randomUUID();
      productRepository.save(
          Product.create().sellerId(sellerId).name("살아있음").price(1_000L).build());
      Product deleted =
          productRepository.save(
              Product.create().sellerId(sellerId).name("삭제됨").price(1_000L).build());
      entityManager.flush();
      UUID deletedId = deleted.getId();
      Instant deletedAt = Instant.parse("2026-06-01T00:00:00Z");
      productRepository.delete(deleted);
      entityManager.flush();
      setDeletedAt(deletedId, deletedAt);
      entityManager.clear();

      // when
      List<ProductTombstone> included =
          productRepository.searchTombstonesSince(Instant.parse("2026-01-01T00:00:00Z"));
      List<ProductTombstone> excluded =
          productRepository.searchTombstonesSince(Instant.parse("2026-12-01T00:00:00Z"));

      // then: 네이티브 조회가 소프트삭제 필터를 우회해 삭제 행만 정확히 반환한다
      assertThat(included).extracting(ProductTombstone::id).containsExactly(deletedId);
      assertThat(included.get(0).deletedAt()).isEqualTo(deletedAt);
      assertThat(excluded).isEmpty();
    }
  }

  private void setChangeTimestamps(UUID id, Instant createdAt, Instant updatedAt) {
    entityManager
        .createNativeQuery(
            "UPDATE product.products SET created_at = :createdAt, updated_at = :updatedAt WHERE id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("updatedAt", updatedAt)
        .setParameter("id", id)
        .executeUpdate();
  }

  private void setDeletedAt(UUID id, Instant deletedAt) {
    entityManager
        .createNativeQuery("UPDATE product.products SET deleted_at = :deletedAt WHERE id = :id")
        .setParameter("deletedAt", deletedAt)
        .setParameter("id", id)
        .executeUpdate();
  }
}

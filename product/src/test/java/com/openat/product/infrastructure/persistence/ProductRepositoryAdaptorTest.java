package com.openat.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.category.domain.model.Category;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
@Import(ProductRepositoryAdaptor.class)
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
}

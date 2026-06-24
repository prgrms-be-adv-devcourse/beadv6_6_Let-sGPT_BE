package com.openat.category.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
@Import(CategoryRepositoryAdaptor.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("카테고리 영속성")
class CategoryRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private CategoryRepository categoryRepository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("저장하면 UUIDv7 id와 생성 시각이 채워진다")
  void save_generatesIdAndCreatedAt() {
    // given
    Category category = Category.create().name("테스트의류").build();

    // when
    Category saved = categoryRepository.save(category);
    entityManager.flush();

    // then
    assertThat(saved.getId()).isNotNull();
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("저장된 이름은 true, 없는 이름은 false를 반환한다")
  void existsByName_reflectsPersistedState() {
    // given
    categoryRepository.save(Category.create().name("테스트액세서리").build());
    entityManager.flush();

    // when & then
    assertThat(categoryRepository.existsByName("테스트액세서리")).isTrue();
    assertThat(categoryRepository.existsByName("존재하지않는이름")).isFalse();
  }

  @Test
  @DisplayName("같은 이름을 두 번 저장하면 유니크 제약을 위반한다")
  void save_duplicateName_violatesUniqueConstraint() {
    // given
    String name = "테스트문구";
    categoryRepository.save(Category.create().name(name).build());

    // when & then
    assertThatThrownBy(
            () -> {
              categoryRepository.save(Category.create().name(name).build());
              entityManager.flush();
            })
        .isInstanceOf(ConstraintViolationException.class);
  }
}

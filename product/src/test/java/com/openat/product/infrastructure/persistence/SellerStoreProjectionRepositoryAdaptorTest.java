package com.openat.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.product.domain.model.SellerStoreProjection;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
@Import(SellerStoreProjectionRepositoryAdaptor.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("판매자 상점 투영 영속성")
class SellerStoreProjectionRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private SellerStoreProjectionRepository sellerStoreProjectionRepository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("외부 식별자를 assigned id로 저장하고 findById로 조회한다")
  void save_assignedId_persistsAndFinds() {
    UUID sellerInfoId = UUID.randomUUID();
    sellerStoreProjectionRepository.save(
        SellerStoreProjection.project()
            .sellerInfoId(sellerInfoId)
            .storeName("스프링 스튜디오")
            .build());
    entityManager.flush();
    entityManager.clear();

    SellerStoreProjection found =
        sellerStoreProjectionRepository.findById(sellerInfoId).orElseThrow();

    assertThat(found.getSellerInfoId()).isEqualTo(sellerInfoId);
    assertThat(found.getStoreName()).isEqualTo("스프링 스튜디오");
    assertThat(found.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("id 목록으로 존재하는 투영만 배치 조회한다")
  void findAllById_returnsProjectedOnly() {
    UUID storeA = UUID.randomUUID();
    UUID storeB = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    sellerStoreProjectionRepository.save(
        SellerStoreProjection.project().sellerInfoId(storeA).storeName("스토어A").build());
    sellerStoreProjectionRepository.save(
        SellerStoreProjection.project().sellerInfoId(storeB).storeName("스토어B").build());
    entityManager.flush();
    entityManager.clear();

    List<SellerStoreProjection> found =
        sellerStoreProjectionRepository.findAllById(List.of(storeA, storeB, missing));

    assertThat(found).hasSize(2);
  }
}

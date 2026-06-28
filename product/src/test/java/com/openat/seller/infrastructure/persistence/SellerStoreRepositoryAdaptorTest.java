package com.openat.seller.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.seller.domain.model.SellerStore;
import com.openat.seller.domain.repository.SellerStoreRepository;
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
@Import(SellerStoreRepositoryAdaptor.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("판매자 스토어 투영 영속성")
class SellerStoreRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private SellerStoreRepository sellerStoreRepository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("외부 식별자(assigned id)로 저장한 투영을 findById로 조회한다")
  void save_assignedId_persistsAndFinds() {
    // given
    UUID sellerInfoId = UUID.randomUUID();
    sellerStoreRepository.save(
        SellerStore.project().sellerInfoId(sellerInfoId).storeName("오픈앳 스튜디오").build());
    entityManager.flush();
    entityManager.clear();

    // when
    SellerStore found = sellerStoreRepository.findById(sellerInfoId).orElseThrow();

    // then
    assertThat(found.getSellerInfoId()).isEqualTo(sellerInfoId);
    assertThat(found.getStoreName()).isEqualTo("오픈앳 스튜디오");
    assertThat(found.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("id 목록으로 투영을 배치 조회하고 미투영은 제외한다")
  void findAllById_returnsProjectedOnly() {
    // given
    UUID storeA = UUID.randomUUID();
    UUID storeB = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    sellerStoreRepository.save(
        SellerStore.project().sellerInfoId(storeA).storeName("스토어A").build());
    sellerStoreRepository.save(
        SellerStore.project().sellerInfoId(storeB).storeName("스토어B").build());
    entityManager.flush();
    entityManager.clear();

    // when
    List<SellerStore> found = sellerStoreRepository.findAllById(List.of(storeA, storeB, missing));

    // then
    assertThat(found).hasSize(2);
  }
}

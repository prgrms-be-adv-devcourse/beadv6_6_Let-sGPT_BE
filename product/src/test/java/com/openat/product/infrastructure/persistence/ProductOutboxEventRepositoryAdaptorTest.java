package com.openat.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.model.ProductOutboxEventStatus;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
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
@Import(ProductOutboxEventRepositoryAdaptor.class)
@TestPropertySource(
    properties = {
      "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
      "spring.sql.init.mode=never"
    })
@DisplayName("상품 outbox 이벤트 영속성")
class ProductOutboxEventRepositoryAdaptorTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

  @Autowired private ProductOutboxEventRepository repository;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @DisplayName("aggregate별 미발행 선행 버전 하나만 claim한다")
  void claimPending_blocksSuccessorUntilPredecessorPublished() {
    UUID aggregateId = UUID.randomUUID();
    ProductOutboxEvent predecessor = save(aggregateId, 1L);
    ProductOutboxEvent successor = save(aggregateId, 2L);
    ProductOutboxEvent independent = save(UUID.randomUUID(), 1L);
    entityManager.flush();
    entityManager.clear();

    Instant firstClaimedAt = Instant.parse("2026-07-31T00:00:00Z");
    List<ProductOutboxEvent> firstClaim =
        repository.claimPending(
            10, firstClaimedAt, firstClaimedAt.minusSeconds(30));

    assertThat(firstClaim)
        .extracting(ProductOutboxEvent::getId)
        .containsExactlyInAnyOrder(predecessor.getId(), independent.getId());
    assertThat(firstClaim)
        .allMatch(event -> event.getStatus() == ProductOutboxEventStatus.PROCESSING);
    repository.markPublished(List.of(predecessor.getId()), firstClaimedAt.plusSeconds(1));

    List<ProductOutboxEvent> secondClaim =
        repository.claimPending(
            10, firstClaimedAt.plusSeconds(2), firstClaimedAt.minusSeconds(30));

    assertThat(secondClaim)
        .extracting(ProductOutboxEvent::getId)
        .containsExactly(successor.getId());
  }

  @Test
  @DisplayName("claim timeout을 지난 PROCESSING 이벤트를 PENDING으로 복구해 다시 claim한다")
  void claimPending_staleProcessing_reclaims() {
    ProductOutboxEvent event = save(UUID.randomUUID(), 1L);
    entityManager.flush();
    entityManager.clear();
    Instant oldClaimedAt = Instant.parse("2026-07-31T00:00:00Z");
    repository.claimPending(1, oldClaimedAt, oldClaimedAt.minusSeconds(30));

    Instant nextClaimedAt = oldClaimedAt.plusSeconds(60);
    List<ProductOutboxEvent> reclaimed =
        repository.claimPending(
            1, nextClaimedAt, nextClaimedAt.minusSeconds(30));

    assertThat(reclaimed).extracting(ProductOutboxEvent::getId).containsExactly(event.getId());
    assertThat(reclaimed.get(0).getClaimedAt()).isEqualTo(nextClaimedAt);
  }

  private ProductOutboxEvent save(UUID aggregateId, long aggregateSequence) {
    return repository.save(
        ProductOutboxEvent.record()
            .aggregateId(aggregateId)
            .aggregateSequence(aggregateSequence)
            .topic("product.updated.events")
            .payload("{\"id\":\"00000000-0000-0000-0000-000000000000\"}")
            .build());
  }
}

package com.openat.search.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.search.config.JacksonConfig;
import com.openat.search.product.application.dto.ProductSearchSyncTestResponse;
import com.openat.search.product.application.dto.ProductSearchSyncTestResponse.ProductSyncOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class ProductTopicProduceTestServiceTest {

  private static final Duration EVENT_TIME_GAP = Duration.ofMinutes(1);

  private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();
  private final ProductTopicProduceTestService service =
      new ProductTopicProduceTestService(mock(KafkaTemplate.class), objectMapper);

  @Test
  void createsFortyInsertsTenUpdatesAndTenDeletesForEverySyncCycle() throws Exception {
    Instant changedAfter = Instant.parse("2030-01-01T00:00:00Z");

    List<ProductSearchSyncTestResponse> firstCycle = service.searchSyncTestProducts(changedAfter);
    Instant firstCycleLastEventAt = latestEventAt(firstCycle);
    List<ProductSearchSyncTestResponse> secondCycle =
        service.searchSyncTestProducts(firstCycleLastEventAt);

    assertCycle(firstCycle, changedAfter);
    assertCycle(secondCycle, firstCycleLastEventAt);
    assertThat(ids(secondCycle)).doesNotContainAnyElementsOf(ids(firstCycle));
    assertThat(objectMapper.readTree(objectMapper.writeValueAsString(firstCycle))).hasSize(60);
  }

  private void assertCycle(List<ProductSearchSyncTestResponse> products, Instant changedAfter) {
    assertThat(products).hasSize(60);
    assertThat(products)
        .filteredOn(product -> product.operation() == ProductSyncOperation.INSERT)
        .hasSize(40);
    assertThat(products)
        .filteredOn(product -> product.operation() == ProductSyncOperation.UPDATE)
        .hasSize(10);
    assertThat(products)
        .filteredOn(product -> product.operation() == ProductSyncOperation.DELETE)
        .hasSize(10);
    assertThat(products).allMatch(product -> product.latestEventAt().isAfter(changedAfter));

    assertEventTimes(products);

    Set<String> insertedIds = ids(products, ProductSyncOperation.INSERT);
    assertThat(ids(products, ProductSyncOperation.UPDATE)).isSubsetOf(insertedIds);
    assertThat(ids(products, ProductSyncOperation.DELETE)).isSubsetOf(insertedIds);
  }

  private Instant latestEventAt(List<ProductSearchSyncTestResponse> products) {
    return products.stream()
        .map(ProductSearchSyncTestResponse::latestEventAt)
        .max(Instant::compareTo)
        .orElseThrow();
  }

  private Set<String> ids(List<ProductSearchSyncTestResponse> products) {
    return products.stream().map(ProductSearchSyncTestResponse::id).collect(Collectors.toSet());
  }

  private Set<String> ids(
      List<ProductSearchSyncTestResponse> products, ProductSyncOperation operation) {
    return products.stream()
        .filter(product -> product.operation() == operation)
        .map(ProductSearchSyncTestResponse::id)
        .collect(Collectors.toSet());
  }

  private void assertEventTimes(List<ProductSearchSyncTestResponse> products) {
    assertThat(products)
        .filteredOn(product -> product.operation() == ProductSyncOperation.INSERT)
        .allSatisfy(
            product -> {
              assertThat(product.updatedAt()).isEqualTo(product.createdAt());
              assertThat(product.deletedAt()).isNull();
            });

    assertThat(products)
        .filteredOn(product -> product.operation() == ProductSyncOperation.UPDATE)
        .allSatisfy(
            product -> {
              assertThat(Duration.between(product.createdAt(), product.updatedAt()))
                  .isEqualTo(EVENT_TIME_GAP);
              assertThat(product.deletedAt()).isNull();
            });

    assertThat(products)
        .filteredOn(product -> product.operation() == ProductSyncOperation.DELETE)
        .allSatisfy(
            product -> {
              assertThat(Duration.between(product.createdAt(), product.updatedAt()))
                  .isEqualTo(EVENT_TIME_GAP);
              assertThat(Duration.between(product.updatedAt(), product.deletedAt()))
                  .isEqualTo(EVENT_TIME_GAP);
            });
  }
}

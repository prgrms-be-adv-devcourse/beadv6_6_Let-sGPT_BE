package com.openat.product.infrastructure.kafka;

import com.openat.product.domain.model.ProductOutboxEventStatus;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ProductOutboxMetrics {

  private final Counter publishedCounter;
  private final Counter failedCounter;

  public ProductOutboxMetrics(
      MeterRegistry meterRegistry, ProductOutboxEventRepository outboxEventRepository) {
    this.publishedCounter =
        Counter.builder("product.outbox.published")
            .description("Successfully published product outbox events")
            .register(meterRegistry);
    this.failedCounter =
        Counter.builder("product.outbox.failed")
            .description("Product outbox events released for retry")
            .register(meterRegistry);
    Gauge.builder(
            "product.outbox.pending",
            outboxEventRepository,
            repository -> repository.countByStatus(ProductOutboxEventStatus.PENDING))
        .description("Pending product outbox events")
        .register(meterRegistry);
    Gauge.builder(
            "product.outbox.processing",
            outboxEventRepository,
            repository -> repository.countByStatus(ProductOutboxEventStatus.PROCESSING))
        .description("Product outbox events currently claimed by a relay")
        .register(meterRegistry);
  }

  public void recordPublished(int count) {
    publishedCounter.increment(count);
  }

  public void recordFailed(int count) {
    failedCounter.increment(count);
  }
}

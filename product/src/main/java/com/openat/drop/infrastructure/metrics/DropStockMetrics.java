package com.openat.drop.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

// 활성 드롭별 잔여 재고 Gauge(product.drop.stock{dropId}). 정적 드롭 목록이 없어 재고 변경(deduct/rollback)
// 시점에 처음 보는 dropId를 온디맨드로 등록(QueueMetricsConfig 패턴)하고, 이후 관측한 잔여값을 홀더에 반영한다.
@Component
public class DropStockMetrics {

  private final MeterRegistry meterRegistry;
  private final ConcurrentHashMap<UUID, AtomicLong> holders = new ConcurrentHashMap<>();

  public DropStockMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void record(UUID dropId, long remaining) {
    holders
        .computeIfAbsent(
            dropId,
            id -> {
              AtomicLong holder = new AtomicLong(remaining);
              meterRegistry.gauge("product.drop.stock", Tags.of("dropId", id.toString()), holder);
              return holder;
            })
        .set(remaining);
  }
}

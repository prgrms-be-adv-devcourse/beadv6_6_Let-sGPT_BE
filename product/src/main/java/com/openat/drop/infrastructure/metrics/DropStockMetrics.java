package com.openat.drop.infrastructure.metrics;

import com.openat.drop.application.port.DropStockMetricsPort;
import com.openat.drop.domain.repository.DropCacheRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 활성 드롭별 잔여 재고 Gauge(product.drop.stock{dropId}). 정적 드롭 목록이 없어 재고 변경(deduct/rollback)
// 시점에 처음 보는 dropId를 온디맨드로 등록한다. Gauge는 응답 완료 순서가 아니라 Redis의 현재값을
// 읽어 동시 요청의 DB 커밋 순서가 뒤집혀도 과거 잔여값으로 역행하지 않는다.
@Component
public class DropStockMetrics implements DropStockMetricsPort {

  private final MeterRegistry meterRegistry;
  private final DropCacheRepository dropCacheRepository;
  private final ConcurrentHashMap<UUID, Gauge> gauges = new ConcurrentHashMap<>();

  public DropStockMetrics(
      MeterRegistry meterRegistry, DropCacheRepository dropCacheRepository) {
    this.meterRegistry = meterRegistry;
    this.dropCacheRepository = dropCacheRepository;
  }

  @Override
  public void register(UUID dropId) {
    gauges.computeIfAbsent(dropId, this::registerGauge);
  }

  private Gauge registerGauge(UUID dropId) {
    return Gauge.builder("product.drop.stock", dropId, this::currentRemaining)
        .description("Current live drop stock read from Redis")
        .tags(Tags.of("dropId", dropId.toString()))
        .register(meterRegistry);
  }

  private double currentRemaining(UUID dropId) {
    try {
      Map<UUID, Long> remaining = dropCacheRepository.findRemaining(List.of(dropId));
      Long value = remaining.get(dropId);
      return value == null ? Double.NaN : value.doubleValue();
    } catch (RuntimeException unavailable) {
      return Double.NaN;
    }
  }
}

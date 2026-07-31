package com.openat.drop.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.openat.drop.domain.repository.DropCacheRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("드롭 재고 지표")
class DropStockMetricsTest {

  @Mock private DropCacheRepository dropCacheRepository;

  @Test
  @DisplayName("Gauge는 명령 완료값이 아니라 측정 시점의 Redis 잔여를 읽는다")
  void gauge_registeredDrop_readsCurrentRedisRemaining() {
    UUID dropId = UUID.randomUUID();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    DropStockMetrics metrics = new DropStockMetrics(meterRegistry, dropCacheRepository);
    given(dropCacheRepository.findRemaining(List.of(dropId)))
        .willReturn(Map.of(dropId, 9L));

    metrics.register(dropId);
    metrics.register(dropId);

    Gauge gauge =
        meterRegistry
            .get("product.drop.stock")
            .tag("dropId", dropId.toString())
            .gauge();
    assertThat(gauge.value()).isEqualTo(9.0);
    given(dropCacheRepository.findRemaining(List.of(dropId)))
        .willReturn(Map.of(dropId, 8L));
    assertThat(gauge.value()).isEqualTo(8.0);
    assertThat(meterRegistry.find("product.drop.stock").gauges()).hasSize(1);
  }

  @Test
  @DisplayName("Redis에 라이브 재고가 없으면 Gauge는 알 수 없음으로 노출한다")
  void gauge_missingCache_reportsNaN() {
    UUID dropId = UUID.randomUUID();
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    DropStockMetrics metrics = new DropStockMetrics(meterRegistry, dropCacheRepository);
    given(dropCacheRepository.findRemaining(List.of(dropId))).willReturn(Map.of());

    metrics.register(dropId);

    Gauge gauge =
        meterRegistry
            .get("product.drop.stock")
            .tag("dropId", dropId.toString())
            .gauge();
    assertThat(gauge.value()).isNaN();
  }
}

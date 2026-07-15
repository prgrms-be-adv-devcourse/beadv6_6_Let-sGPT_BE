package com.openat.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("상품 Kafka producer 설정")
class ProductKafkaProducerConfigTest {

  @Test
  @DisplayName("브로커 메타데이터 대기 시간을 3초로 제한한다")
  void productProducerFactory_validConfig_limitsMetadataWait() {
    // given
    ProductKafkaProducerConfig config = new ProductKafkaProducerConfig();
    ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");

    // when
    ProducerFactory<String, String> producerFactory = config.productProducerFactory();

    // then
    assertThat(producerFactory).isInstanceOf(DefaultKafkaProducerFactory.class);
    DefaultKafkaProducerFactory<?, ?> defaultProducerFactory =
        (DefaultKafkaProducerFactory<?, ?>) producerFactory;
    assertThat(defaultProducerFactory.getConfigurationProperties())
        .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000L);
  }
}

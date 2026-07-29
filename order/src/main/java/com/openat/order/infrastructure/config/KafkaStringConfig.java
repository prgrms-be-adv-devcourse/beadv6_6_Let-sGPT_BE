package com.openat.order.infrastructure.config;

import com.openat.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaStringConfig {

  @Bean
  public ProducerFactory<String, String> producerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
      MeterRegistry meterRegistry) {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    // 셋을 함께 유지 — delivery.timeout.ms >= linger.ms + request.timeout.ms 위반 시 부팅 실패
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
    factory.addListener(new MicrometerProducerListener<>(meterRegistry));
    return factory;
  }

  @Bean
  public KafkaTemplate<String, String> kafkaTemplate(
      ProducerFactory<String, String> producerFactory,
      @Value("${spring.kafka.template.observation-enabled:false}") boolean observationEnabled) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
    // observation-enabled는 Boot 자동설정 KafkaTemplate에만 먹는다 — 직접 등록한 이 빈은 수동 적용해야 producer 스팬이 생긴다
    template.setObservationEnabled(observationEnabled);
    return template;
  }

  @Bean
  public ConsumerFactory<String, String> consumerFactory(
      @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
      @Value("${spring.kafka.consumer.group-id}") String groupId,
      @Value("${spring.kafka.consumer.auto-offset-reset:earliest}") String autoOffsetReset,
      MeterRegistry meterRegistry) {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
    // Boot 자동설정 ConsumerFactory가 붙여주던 리스너 — 직접 등록한 팩토리라 수동 부착해야 컨슈머 랙·페치 지표가 나온다
    factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
    return factory;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory,
      DefaultErrorHandler defaultErrorHandler,
      @Value("${spring.kafka.listener.concurrency:1}") int concurrency) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(defaultErrorHandler);
    factory.setConcurrency(concurrency);
    return factory;
  }

  @Bean
  public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate, (record, exception) -> deadLetterPartition(record.topic()));
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));
    errorHandler.addNotRetryableExceptions(BusinessException.class);
    return errorHandler;
  }

  static TopicPartition deadLetterPartition(String topic) {
    return new TopicPartition(topic + ".dlq", 0);
  }
}

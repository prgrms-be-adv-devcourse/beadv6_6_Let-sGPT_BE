package com.openat.payment.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;

// Spring Boot 4.1의 기본 자동설정 KafkaTemplate은 제네릭이 KafkaTemplate<Object, Object>라
// KafkaTemplate<String, String> 주입 지점과 타입이 안 맞음 — outbox 발행용으로 명시적으로 직접 등록.
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        // 자동설정 ProducerFactory가 아니라 직접 등록한 팩토리라 Boot가 리스너를 안 붙여준다 —
        // 이걸 붙여야 카프카 클라이언트 내부 지표(kafka_producer_request_latency_avg,
        // record_send_total, buffer_available_bytes 등)가 프로메테우스로 노출된다.
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(
            ProducerFactory<String, String> producerFactory,
            @Value("${spring.kafka.template.observation-enabled:false}") boolean observationEnabled) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // spring.kafka.template.observation-enabled는 Boot가 자동설정한 KafkaTemplate에만 먹는다.
        // 이 템플릿은 직접 등록한 빈이라 같은 프로퍼티를 읽어 수동으로 적용해야 producer 스팬이 생긴다.
        template.setObservationEnabled(observationEnabled);
        return template;
    }
}

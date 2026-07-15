package com.openat.payment.infrastructure.messaging.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// Consumer 처리 실패 시 3회 재시도 후 {source-topic}.DLQ로 보낸다(settlement KafkaConsumerErrorHandlerConfig와
// 동일 규칙 — 7/15 DLQ WS-1, 접미사 .DLQ 통일).
@Configuration
public class KafkaConsumerErrorHandlerConfig {

    @Bean
    public DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLQ", record.partition()));

        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));
    }
}

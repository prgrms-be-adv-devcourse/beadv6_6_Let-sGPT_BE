package com.openat.settlement.infrastructure.kafka.dlq;

import com.openat.settlement.application.dto.DlqReprocessResult;
import com.openat.settlement.infrastructure.kafka.consumer.PaymentEventConsumer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// payment.settlement.events.DLQ 수동 재처리(7/15 DLQ WS-1) — payment 모듈 OrderCompletedDlqReprocessor와
// 동일한 최소 구현(호출마다 독립 컨슈머, 실패 파티션은 커밋 보류해서 다음 호출에 재시도).
// 파싱/분기 로직은 중복 없이 기존 PaymentEventConsumer.consume()을 그대로 재사용한다.
@Slf4j
@Component
public class PaymentEventDlqReprocessor {

    private static final String GROUP_ID = "settlement-service-dlq-reprocessor";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration OVERALL_BUDGET = Duration.ofSeconds(5);

    private final String bootstrapServers;
    private final String dlqTopic;
    private final PaymentEventConsumer paymentEventConsumer;

    public PaymentEventDlqReprocessor(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${settlement.kafka.topic.payment-events}") String sourceTopic,
            PaymentEventConsumer paymentEventConsumer) {
        this.bootstrapServers = bootstrapServers;
        this.dlqTopic = sourceTopic + ".DLQ";
        this.paymentEventConsumer = paymentEventConsumer;
    }

    public DlqReprocessResult reprocess(int max) {
        List<ConsumerRecord<String, String>> batch = pollBatch(max);

        int reprocessed = 0;
        int failed = 0;
        Map<TopicPartition, OffsetAndMetadata> commitOffsets = new HashMap<>();
        Map<TopicPartition, Boolean> partitionFailed = new HashMap<>();

        for (ConsumerRecord<String, String> record : batch) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            if (Boolean.TRUE.equals(partitionFailed.get(partition))) {
                continue;
            }
            try {
                paymentEventConsumer.consume(record.value());
                reprocessed++;
                commitOffsets.put(partition, new OffsetAndMetadata(record.offset() + 1));
            } catch (Exception e) {
                failed++;
                partitionFailed.put(partition, true);
                log.error("[PaymentEventDlqReprocessor] 재처리 실패, partition={} offset={} 이후 커밋 보류",
                        partition, record.offset(), e);
            }
        }

        if (!commitOffsets.isEmpty()) {
            commitSync(commitOffsets);
        }
        return new DlqReprocessResult(batch.size(), reprocessed, failed);
    }

    private List<ConsumerRecord<String, String>> pollBatch(int max) {
        Properties props = consumerProps();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dlqTopic));
            List<ConsumerRecord<String, String>> batch = new ArrayList<>();
            long deadline = System.currentTimeMillis() + OVERALL_BUDGET.toMillis();
            while (batch.size() < max && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, String> record : polled) {
                    batch.add(record);
                    if (batch.size() >= max) {
                        break;
                    }
                }
                if (polled.isEmpty() && !batch.isEmpty()) {
                    break;
                }
            }
            // 커밋하지 않는다 — reprocess()가 성공분 오프셋만 골라 별도 커밋한다(ENABLE_AUTO_COMMIT_CONFIG=false).
            return batch;
        } catch (Exception e) {
            log.error("[PaymentEventDlqReprocessor] DLQ poll 실패", e);
            return List.of();
        }
    }

    private void commitSync(Map<TopicPartition, OffsetAndMetadata> commitOffsets) {
        Properties props = consumerProps();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.assign(commitOffsets.keySet());
            consumer.commitSync(commitOffsets);
        }
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}

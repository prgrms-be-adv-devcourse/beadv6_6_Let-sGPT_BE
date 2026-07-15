package com.openat.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.dto.DlqReprocessResult;
import com.openat.payment.application.usecase.PaymentUseCase;
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

// order.completed.events.DLQ 수동 재처리(7/15 DLQ WS-1) — 호출마다 독립 컨슈머를 만들어 짧게 poll하고 닫는다.
// 실패한 레코드가 있는 파티션은 그 지점 이후 오프셋을 커밋하지 않는다 — 다음 호출에서 실패 레코드부터 다시 읽힌다.
// backfillSellerAndProduct가 멱등(#14)이라 이미 성공한 레코드가 같은 파티션에서 함께 재읽혀도 안전.
@Slf4j
@Component
public class OrderCompletedDlqReprocessor {

    private static final String DLQ_TOPIC = "order.completed.events.DLQ";
    private static final String GROUP_ID = "payment-service-dlq-reprocessor";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration OVERALL_BUDGET = Duration.ofSeconds(5);

    private final String bootstrapServers;
    private final PaymentUseCase paymentUseCase;
    private final ObjectMapper objectMapper;

    public OrderCompletedDlqReprocessor(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            PaymentUseCase paymentUseCase, ObjectMapper objectMapper) {
        this.bootstrapServers = bootstrapServers;
        this.paymentUseCase = paymentUseCase;
        this.objectMapper = objectMapper;
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
                continue; // 이 파티션은 이미 실패 지점 지남 — 오프셋 커밋 더 진행 안 함
            }
            try {
                OrderCompletedEvent event = objectMapper.readValue(record.value(), OrderCompletedEvent.class);
                paymentUseCase.backfillSellerAndProduct(event.orderId(), event.sellerId(), event.productId());
                reprocessed++;
                commitOffsets.put(partition, new OffsetAndMetadata(record.offset() + 1));
            } catch (Exception e) {
                failed++;
                partitionFailed.put(partition, true);
                log.error("[OrderCompletedDlqReprocessor] 재처리 실패, partition={} offset={} 이후 커밋 보류",
                        partition, record.offset(), e);
            }
        }

        if (!commitOffsets.isEmpty()) {
            commitSync(commitOffsets);
        }
        return new DlqReprocessResult(batch.size(), reprocessed, failed);
    }

    private List<ConsumerRecord<String, String>> pollBatch(int max) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(DLQ_TOPIC));
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
                    break; // 첫 배치 이후 더 이상 없음 — 조기 종료
                }
            }
            // 여기서는 커밋하지 않는다 — reprocess()가 성공분 오프셋만 골라 별도 커밋(commitSync 헬퍼)한다.
            // 이 컨슈머를 닫을 때 auto-commit도 꺼져 있어(ENABLE_AUTO_COMMIT_CONFIG=false) 묵시적 커밋 없음.
            return batch;
        } catch (Exception e) {
            log.error("[OrderCompletedDlqReprocessor] DLQ poll 실패", e);
            return List.of();
        }
    }

    private void commitSync(Map<TopicPartition, OffsetAndMetadata> commitOffsets) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.assign(commitOffsets.keySet());
            consumer.commitSync(commitOffsets);
        }
    }
}

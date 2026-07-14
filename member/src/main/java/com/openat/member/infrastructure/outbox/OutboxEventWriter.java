package com.openat.member.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

// 호출하는 쪽의 @Transactional 경계 안에서 같은 트랜잭션으로 outbox row를 적재(payment의 outbox 최소 버전 이식).
@Component
public class OutboxEventWriter {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventJpaRepository outboxEventJpaRepository, ObjectMapper objectMapper) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, UUID aggregateId, String topic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxEventJpaRepository.save(new OutboxEventJpaEntity(aggregateType, aggregateId, topic, json));
        } catch (Exception e) {
            throw new IllegalStateException("outbox 이벤트 직렬화 실패: " + topic, e);
        }
    }
}

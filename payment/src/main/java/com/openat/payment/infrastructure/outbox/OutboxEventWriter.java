package com.openat.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.domain.model.support.UuidV7Generator;
import java.util.UUID;
import org.springframework.stereotype.Component;

// 호출하는 쪽의 @Transactional 경계 안에서 같은 트랜잭션으로 outbox row를 적재(A8 outbox 최소 버전).
@Component
public class OutboxEventWriter implements DomainEventPublisher {

  private final OutboxEventJpaRepository outboxEventJpaRepository;
  private final ObjectMapper objectMapper;

  public OutboxEventWriter(
      OutboxEventJpaRepository outboxEventJpaRepository, ObjectMapper objectMapper) {
    this.outboxEventJpaRepository = outboxEventJpaRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public void publish(String aggregateType, UUID aggregateId, String topic, Object payload) {
    try {
      String json = objectMapper.writeValueAsString(payload);
      outboxEventJpaRepository.save(
          new OutboxEventJpaEntity(
              UuidV7Generator.generate(), aggregateType, aggregateId, topic, json));
    } catch (Exception e) {
      throw new IllegalStateException("outbox 이벤트 직렬화 실패: " + topic, e);
    }
  }
}

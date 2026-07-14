package com.openat.payment.application.event;

import java.util.UUID;

// outbox 적재의 아웃바운드 포트(A8) — application은 infrastructure(OutboxEventWriter)를 모른다(DIP 복원, code_review
// §5.2).
// TossPaymentClient와 동일한 포트-어댑터 정석 구조로 통일.
public interface DomainEventPublisher {

  void publish(String aggregateType, UUID aggregateId, String topic, Object payload);
}

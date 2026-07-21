package com.openat.payment.infrastructure.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(OutboxEventJpaEntity.Status status);

    long countByStatus(OutboxEventJpaEntity.Status status);

    // 미발행 알림(A8/§9 정식화) — PENDING 상태로 N분 넘게 남아있는 row 탐지용.
    List<OutboxEventJpaEntity> findByStatusAndCreatedAtBefore(OutboxEventJpaEntity.Status status,
            LocalDateTime threshold);
}

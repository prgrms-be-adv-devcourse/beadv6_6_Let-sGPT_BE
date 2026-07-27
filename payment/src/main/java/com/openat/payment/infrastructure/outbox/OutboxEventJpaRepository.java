package com.openat.payment.infrastructure.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    // 폴링 조회에는 반드시 배치 상한(Pageable)을 준다 — 적체가 쌓였을 때 한 tick이 전체 PENDING을
    // 통째로 읽어 오래 도는 것을 막는다.
    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(OutboxEventJpaEntity.Status status,
            Pageable pageable);

    long countByStatus(OutboxEventJpaEntity.Status status);

    // 미발행 알림(A8/§9 정식화) — PENDING 상태로 N분 넘게 남아있는 row 탐지용.
    List<OutboxEventJpaEntity> findByStatusAndCreatedAtBefore(OutboxEventJpaEntity.Status status,
            LocalDateTime threshold);
}

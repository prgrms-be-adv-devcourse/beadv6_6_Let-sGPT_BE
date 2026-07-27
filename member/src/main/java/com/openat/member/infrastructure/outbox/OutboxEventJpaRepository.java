package com.openat.member.infrastructure.outbox;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    // Pageable로 상한을 강제한다 — 적체가 쌓여도 한 폴링 tick이 무한정 길어지지 않도록
    // 조회 단계에서부터 배치 크기를 제한한다(payment의 OutboxEventJpaRepository와 동일 의도).
    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(
            OutboxEventJpaEntity.Status status, Pageable pageable);

    long countByStatus(OutboxEventJpaEntity.Status status);

    // Kafka ack를 받은 id만 모아 한 번에 확정한다. 조건부(status = PENDING)라 이미 다른 경로에서
    // 확정된 행은 건드리지 않고, 갱신된 건수를 그대로 발행 카운터에 반영할 수 있다.
    // 이 메서드 호출 자체가 짧은 트랜잭션 하나 — 발행 대기 구간에는 커넥션을 쥐지 않는다
    // (payment의 markPublished와 동일 패턴).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update OutboxEventJpaEntity event "
            + "set event.status = :publishedStatus, event.publishedAt = :publishedAt "
            + "where event.id in :ids and event.status = :pendingStatus")
    int markPublished(@Param("ids") Collection<UUID> ids,
            @Param("publishedStatus") OutboxEventJpaEntity.Status publishedStatus,
            @Param("pendingStatus") OutboxEventJpaEntity.Status pendingStatus,
            @Param("publishedAt") LocalDateTime publishedAt);
}

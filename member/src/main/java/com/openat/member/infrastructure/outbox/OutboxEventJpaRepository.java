package com.openat.member.infrastructure.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    // Pageable로 상한을 강제한다 — 적체가 쌓여도 한 폴링 tick이 무한정 길어지지 않도록
    // 조회 단계에서부터 배치 크기를 제한한다(order의 OutboxEventRepository.findPending과 동일 의도).
    List<OutboxEventJpaEntity> findByStatusOrderByCreatedAtAsc(
            OutboxEventJpaEntity.Status status, Pageable pageable);
}

package com.openat.payment.infrastructure.outbox;

import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// outbox 미발행 알림 정식화(A8/§9) — "DB엔 있는데 Kafka로 발행이 안 됨"을 능동적으로 잡아내는 관측 채널.
// 알림 발신 채널(Slack 등)은 파이널 범위 — 지금은 운영자가 보는 로그에 경고로 남기는 최소 버전.
@Slf4j
@Component
public class OutboxStaleAlertChecker {

    private final OutboxEventJpaRepository outboxEventJpaRepository;

    // N분 — 이 시간 안에 발행 못 하면 "미발행 지연"으로 간주. 운영 기본값 5분, 시연/테스트 시 짧게 오버라이드.
    @Value("${payment.outbox.unpublished-alert-threshold-minutes:5}")
    private long unpublishedAlertThresholdMinutes;

    public OutboxStaleAlertChecker(OutboxEventJpaRepository outboxEventJpaRepository) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
    }

    @Scheduled(fixedDelay = 60_000)
    public void checkStalePending() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(unpublishedAlertThresholdMinutes);
        List<OutboxEventJpaEntity> stale =
                outboxEventJpaRepository.findByStatusAndCreatedAtBefore(OutboxEventJpaEntity.Status.PENDING, threshold);
        if (stale.isEmpty()) {
            return;
        }
        for (OutboxEventJpaEntity event : stale) {
            log.warn("[OutboxStaleAlertChecker] outbox 미발행 {}분 초과: id={}, topic={}, aggregateId={}",
                    unpublishedAlertThresholdMinutes, event.getId(), event.getTopic(), event.getAggregateId());
        }
    }
}

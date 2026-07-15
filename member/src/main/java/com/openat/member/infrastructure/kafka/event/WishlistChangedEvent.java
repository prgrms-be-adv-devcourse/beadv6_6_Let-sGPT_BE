package com.openat.member.infrastructure.kafka.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 찜 추가/삭제 이벤트. {@code type}으로 create/delete를 판별한다.
 *
 * <p>create/delete를 별도 토픽으로 나누지 않고 단일 토픽({@code wishlist.changed.events})에
 * 이 필드로 실어 보낸다 — 별도 토픽은 다른 파티션에 배치되어 같은 회원의 "삭제→생성" 순서가
 * 깨질 수 있기 때문. Kafka 발행 key는 {@code userId}로 고정해 같은 회원의 이벤트는 항상 같은
 * 파티션에서 순서대로 처리된다({@link com.openat.member.infrastructure.outbox.OutboxPublisher} 참고).
 *
 * <p>{@code occurredAt}은 at-least-once 재전송/재정렬 상황에서 consumer가 stale 이벤트를
 * 판별할 수 있도록 포함한다.
 */
public record WishlistChangedEvent(
        UUID userId,
        UUID productId,
        ChangeType type,
        Instant occurredAt
) {

    public enum ChangeType {
        CREATE, DELETE
    }
}

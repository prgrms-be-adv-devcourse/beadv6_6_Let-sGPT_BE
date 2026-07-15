package com.openat.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboxEventTest {

    @Test
    @DisplayName("실패 상태는 processedAt을 성공 처리 시각으로 남기지 않는다")
    void markFailed_keepsProcessedAtEmpty() {
        InboxEvent event = InboxEvent.receive()
                .eventId("payment.completed:payment:order")
                .eventType("payment.completed")
                .payload("{}")
                .build();
        event.markProcessed(Instant.now());

        event.markFailed("processing failed");

        assertThat(event.getStatus()).isEqualTo(InboxEventStatus.FAILED);
        assertThat(event.getErrorMessage()).isEqualTo("processing failed");
        assertThat(event.getProcessedAt()).isNull();
    }
}

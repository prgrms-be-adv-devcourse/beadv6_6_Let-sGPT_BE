package com.openat.payment.infrastructure.webhook;

import java.util.UUID;
import lombok.Getter;

// 리스너에게 통지되는 결과 — 리스너는 전체 도메인 객체가 아니라 식별자/타입/성공여부만 필요(outbox 적재, 메트릭 등).
@Getter
public final class WebhookOutcome {

    private final String handlerType;

    private final boolean success;

    private final UUID referenceId;

    public WebhookOutcome(String handlerType, boolean success, UUID referenceId) {
        this.handlerType = handlerType;
        this.success = success;
        this.referenceId = referenceId;
    }
}

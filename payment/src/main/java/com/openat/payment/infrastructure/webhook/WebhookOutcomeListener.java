package com.openat.payment.infrastructure.webhook;

// Observer — outbox 적재(Day2), 메트릭 등 후속처리를 추가할 때 AbstractPgWebhookHandler 본체를 안 건드리기 위한 확장 지점.
public interface WebhookOutcomeListener {

    void onOutcome(WebhookOutcome outcome);
}

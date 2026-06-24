package com.openat.payment.infrastructure.webhook;

import lombok.Getter;

// 파싱 전 원문 문자열을 그대로 들고 있음 — 구체 핸들러가 필요한 필드만 파싱.
@Getter
public class WebhookRequest {

    private final String rawBody;

    public WebhookRequest(String rawBody) {
        this.rawBody = rawBody;
    }
}

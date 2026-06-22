package com.openat.payment.infrastructure.webhook;

import lombok.Getter;

// 서명 검증은 원문 바이트(rawBody) 기준이어야 해서 파싱 전 문자열을 그대로 들고 있음 — 구체 핸들러가 필요한 필드만 파싱.
@Getter
public class WebhookRequest {

    private final String rawBody;

    private final String signatureHeader;

    public WebhookRequest(String rawBody, String signatureHeader) {
        this.rawBody = rawBody;
        this.signatureHeader = signatureHeader;
    }
}

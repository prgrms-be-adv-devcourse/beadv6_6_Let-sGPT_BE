package com.openat.payment.infrastructure.webhook;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public final class WebhookResult {

    private final HttpStatus status;

    private WebhookResult(HttpStatus status) {
        this.status = status;
    }

    // 처리 성공/실패/이미처리됨 모두 PG 입장에선 "수신 확인" — 재전송을 막기 위해 200으로 응답(§4 토스 재전송 정책).
    public static WebhookResult ok() {
        return new WebhookResult(HttpStatus.OK);
    }

    // 서명 검증 실패만 401 — PG가 아닌 발신자의 위조/재생 시도로 간주.
    public static WebhookResult unauthorized() {
        return new WebhookResult(HttpStatus.UNAUTHORIZED);
    }
}

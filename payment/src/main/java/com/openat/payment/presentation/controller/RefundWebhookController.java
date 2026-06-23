package com.openat.payment.presentation.controller;

import com.openat.payment.infrastructure.webhook.RefundWebhookHandler;
import com.openat.payment.infrastructure.webhook.WebhookRequest;
import com.openat.payment.infrastructure.webhook.WebhookResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Refund Webhook", description = "PG가 호출하는 환불 완료/실패 보조 확정 채널(프론트 직접 호출 대상 아님)")
@RestController
@RequestMapping("/api/v1/refunds/webhook")
public class RefundWebhookController {

    private final RefundWebhookHandler refundWebhookHandler;

    public RefundWebhookController(RefundWebhookHandler refundWebhookHandler) {
        this.refundWebhookHandler = refundWebhookHandler;
    }

    @Operation(summary = "환불 웹훅 수신", description = "환불 PG 호출이 타임아웃돼 결과를 못 받은 경우의 보조 확정 채널. signature는 HMAC-SHA256(공유 비밀키) 자체 규약.")
    @PostMapping
    public ResponseEntity<Void> receive(
            @Parameter(description = "HMAC-SHA256(rawBody, PG_SECRET_KEY) 서명")
            @RequestHeader(value = "signature", required = false) String signature,
            @RequestBody String rawBody) {
        WebhookResult result = refundWebhookHandler.handle(new WebhookRequest(rawBody, signature));
        return ResponseEntity.status(result.getStatus()).build();
    }
}

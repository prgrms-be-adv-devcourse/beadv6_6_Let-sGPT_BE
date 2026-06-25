package com.openat.payment.presentation.controller;

import com.openat.payment.infrastructure.webhook.RefundWebhookHandler;
import com.openat.payment.infrastructure.webhook.WebhookRequest;
import com.openat.payment.infrastructure.webhook.WebhookResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    // I1(2026-06-24) — 서명검증 대신 PG 재조회(queryRefundStatus)가 source of truth.
    @Operation(summary = "환불 웹훅 수신", description = "환불 PG 호출이 타임아웃돼 결과를 못 받은 경우의 보조 확정 채널. PG 재조회로 상태를 재검증한다(I1).")
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody) {
        WebhookResult result = refundWebhookHandler.handle(new WebhookRequest(rawBody));
        return ResponseEntity.status(result.getStatus()).build();
    }
}

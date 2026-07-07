package com.openat.payment.presentation.controller;

import com.openat.payment.infrastructure.webhook.WalletChargeWebhookHandler;
import com.openat.payment.infrastructure.webhook.WebhookRequest;
import com.openat.payment.infrastructure.webhook.WebhookResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Wallet Charge Webhook", description = "PG가 호출하는 충전 승인/거절 보조 확정 채널(프론트 직접 호출 대상 아님)")
@RestController
@RequestMapping("/api/v1/wallet/charge/webhook")
public class WalletChargeWebhookController {

    private final WalletChargeWebhookHandler walletChargeWebhookHandler;

    public WalletChargeWebhookController(WalletChargeWebhookHandler walletChargeWebhookHandler) {
        this.walletChargeWebhookHandler = walletChargeWebhookHandler;
    }

    // I1(2026-06-24) — 서명검증 대신 PG 재조회(queryPaymentStatus)가 source of truth.
    @Operation(summary = "충전 웹훅 수신", description = "confirm 호출이 타임아웃돼 결과를 못 받은 경우의 보조 확정 채널. PG 재조회로 상태를 재검증한다(I1).")
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String rawBody) {
        WebhookResult result = walletChargeWebhookHandler.handle(new WebhookRequest(rawBody));
        return ResponseEntity.status(result.getStatus()).build();
    }
}

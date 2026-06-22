package com.openat.payment.presentation.controller;

import com.openat.payment.infrastructure.webhook.WalletChargeWebhookHandler;
import com.openat.payment.infrastructure.webhook.WebhookRequest;
import com.openat.payment.infrastructure.webhook.WebhookResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet/charge/webhook")
public class WalletChargeWebhookController {

    private final WalletChargeWebhookHandler walletChargeWebhookHandler;

    public WalletChargeWebhookController(WalletChargeWebhookHandler walletChargeWebhookHandler) {
        this.walletChargeWebhookHandler = walletChargeWebhookHandler;
    }

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestHeader(value = "signature", required = false) String signature,
            @RequestBody String rawBody) {
        WebhookResult result = walletChargeWebhookHandler.handle(new WebhookRequest(rawBody, signature));
        return ResponseEntity.status(result.getStatus()).build();
    }
}

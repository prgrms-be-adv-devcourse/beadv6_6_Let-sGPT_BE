package com.openat.settlement.presentation.controller;

import com.openat.settlement.application.dto.DlqReprocessResult;
import com.openat.settlement.infrastructure.kafka.dlq.PaymentEventDlqReprocessor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// DLQ 수동 재처리 트리거(7/15 DLQ WS-1 DoD) — 원인 수정 후 운영자가 호출.
@RestController
public class DlqReprocessController {

    private final PaymentEventDlqReprocessor paymentEventDlqReprocessor;

    public DlqReprocessController(PaymentEventDlqReprocessor paymentEventDlqReprocessor) {
        this.paymentEventDlqReprocessor = paymentEventDlqReprocessor;
    }

    @PostMapping("/internal/v1/dlq/payment-events/reprocess")
    public DlqReprocessResult reprocessPaymentEvents(@RequestParam(defaultValue = "100") int max) {
        return paymentEventDlqReprocessor.reprocess(max);
    }
}

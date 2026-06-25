package com.openat.payment.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

// payment.completed.events 발행 페이로드 — WALLET 즉시승인/PG confirm승인/PG 보조웹훅승인 세 경로가 공유.
public record PaymentCompletedPayload(UUID paymentId, UUID orderId, UUID memberId, Long amount, String method,
        String pgTxId, LocalDateTime approvedAt) {
}

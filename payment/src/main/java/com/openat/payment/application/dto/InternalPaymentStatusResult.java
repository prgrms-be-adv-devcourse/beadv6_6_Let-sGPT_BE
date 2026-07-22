package com.openat.payment.application.dto;

import java.util.UUID;

// 내부 결제 상태 조회(GET /internal/v1/payments) 결과. status는 Payment.Status 이름 그대로(7종).
public record InternalPaymentStatusResult(UUID paymentId, String status, Long amount) {}

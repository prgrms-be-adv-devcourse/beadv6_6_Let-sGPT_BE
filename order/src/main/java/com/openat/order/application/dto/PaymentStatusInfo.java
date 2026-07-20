package com.openat.order.application.dto;

import java.util.UUID;

public record PaymentStatusInfo(UUID paymentId, PaymentStatus status, Long amount) {}

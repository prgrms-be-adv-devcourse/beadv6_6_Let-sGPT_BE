package com.openat.order.infrastructure.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentFailedEvent(
        UUID orderId,
        UUID paymentId,
        String reason
) {
}

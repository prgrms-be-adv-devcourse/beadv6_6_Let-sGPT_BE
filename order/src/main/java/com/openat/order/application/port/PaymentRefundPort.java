package com.openat.order.application.port;

import com.openat.order.application.dto.PaymentRefundResult;
import java.util.UUID;

public interface PaymentRefundPort {

  PaymentRefundResult requestRefund(UUID orderId, String idempotencyKey);
}

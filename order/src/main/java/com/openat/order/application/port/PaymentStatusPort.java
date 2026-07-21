package com.openat.order.application.port;

import com.openat.order.application.dto.PaymentStatusInfo;
import java.util.UUID;

public interface PaymentStatusPort {

  PaymentStatusInfo findByOrderId(UUID orderId);
}

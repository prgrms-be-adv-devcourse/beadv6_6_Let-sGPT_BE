package com.openat.order.application.port;

import com.openat.order.application.dto.ProductOrderSnapshot;
import java.util.UUID;

public interface ProductOrderPort {

    ProductOrderSnapshot getOrderSnapshot(UUID dropId);

    void decreaseStock(UUID dropId, UUID orderId, int quantity, String idempotencyKey);

    void restoreStock(UUID dropId, UUID orderId, int quantity, String idempotencyKey);
}

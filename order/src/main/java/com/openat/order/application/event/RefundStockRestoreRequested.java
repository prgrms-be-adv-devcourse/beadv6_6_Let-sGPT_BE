package com.openat.order.application.event;

import java.util.UUID;

public record RefundStockRestoreRequested(UUID orderId, UUID dropId, UUID memberId, int quantity) {}

package com.openat.order.application.dto;

import java.util.UUID;

public record CreateOrderCommand(UUID dropId, int quantity, String idempotencyKey) {}

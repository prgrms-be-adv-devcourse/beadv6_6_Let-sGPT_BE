package com.openat.drop.domain.repository;

import java.util.UUID;

public record StockMutation(UUID dropId, UUID orderId, UUID buyerId, int quantity) {}

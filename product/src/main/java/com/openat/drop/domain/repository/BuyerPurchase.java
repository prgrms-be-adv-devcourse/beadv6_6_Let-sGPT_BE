package com.openat.drop.domain.repository;

import java.util.UUID;

public record BuyerPurchase(UUID buyerId, long quantity) {}

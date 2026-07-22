package com.openat.chat.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CryptoPriceResult(
    String asset, String currency, BigDecimal price, Instant lastUpdatedAt, String sourceUrl) {}

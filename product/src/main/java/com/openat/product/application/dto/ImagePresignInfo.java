package com.openat.product.application.dto;

import java.time.Instant;

public record ImagePresignInfo(String stagingKey, String uploadUrl, Instant expiresAt) {}

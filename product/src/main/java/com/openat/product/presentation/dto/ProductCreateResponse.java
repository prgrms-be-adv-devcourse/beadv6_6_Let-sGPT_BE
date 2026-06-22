package com.openat.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ProductCreateResponse(@Schema(description = "생성된 상품 식별자") UUID id) {}

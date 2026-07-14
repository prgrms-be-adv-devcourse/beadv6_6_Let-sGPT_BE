package com.openat.member.application.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** 찜 추가(POST) 요청. */
public record AddWishlistRequest(
        @NotNull UUID productId
) {
}

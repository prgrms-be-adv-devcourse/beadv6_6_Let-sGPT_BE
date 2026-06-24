package com.openat.member.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 판매자 정보 신규 등록(POST). */
public record CreateSellerInfoRequest(
        @NotBlank @Size(max = 30) String businessNumber,
        @NotBlank @Size(max = 50) String storeName
) {
}

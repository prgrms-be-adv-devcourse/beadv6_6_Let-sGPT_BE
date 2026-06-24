package com.openat.member.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 판매자 정보 부분 수정(PATCH) — storeName만 변경 가능. 활성 SellerInfo가 있어야만 호출 가능.
 */
public record PatchSellerInfoRequest(
        @NotBlank @Size(max = 50) String storeName
) {
}

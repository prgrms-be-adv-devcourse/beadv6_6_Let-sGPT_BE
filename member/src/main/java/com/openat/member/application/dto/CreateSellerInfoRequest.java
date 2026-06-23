package com.openat.member.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 판매자 정보 최초 등록(POST) — 활성(논리적 삭제되지 않은) SellerInfo가 없을 때만 호출 가능.
 */
public record CreateSellerInfoRequest(
        @NotBlank @Size(max = 30) String businessNumber,
        @NotBlank @Size(max = 50) String storeName
) {
}

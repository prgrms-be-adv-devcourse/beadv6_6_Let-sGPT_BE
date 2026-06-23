package com.openat.member.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 판매자 정보 전체 교체(PUT) — businessNumber/storeName을 동시에 수정한다.
 * 기존에 활성 SellerInfo가 있으면 논리적 삭제하고, 이번 요청 값으로 새로 생성한다.
 */
public record UpdateSellerInfoRequest(
        @NotBlank @Size(max = 30) String businessNumber,
        @NotBlank @Size(max = 50) String storeName
) {
}

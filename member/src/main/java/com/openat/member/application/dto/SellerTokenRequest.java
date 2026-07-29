package com.openat.member.application.dto;

import java.util.UUID;

/**
 * 판매자 scoped 토큰 발급 요청.
 *
 * <p>게이트웨이가 회원 JWT를 검증하고 X-User-Id(memberId)를 주입하므로
 * 바디에는 스토어(sellerInfoId)와 토큰 사용 목적(audience/scope)을 담는다.
 * audience를 생략하면 기존 클라이언트 호환을 위해 상품 쓰기 토큰을 발급하고,
 * scope를 생략하면 audience에 맞는 기본 scope를 적용한다.
 */
public record SellerTokenRequest(
        UUID sellerInfoId,
        String audience,
        String scope
) {

    public SellerTokenRequest(UUID sellerInfoId) {
        this(sellerInfoId, null, null);
    }
}

package com.openat.member.application.dto;

/**
 * 판매자 scoped 토큰 발급 응답.
 *
 * <p>scoped 토큰은 수명이 짧고(기본 120초) 회원 JWT로 재발급하므로
 * refresh 토큰을 별도로 두지 않는다.
 */
public record SellerTokenResponse(
        String tokenType,
        String accessToken,
        long expiresIn
) {

    public static SellerTokenResponse of(String accessToken, long expiresIn) {
        return new SellerTokenResponse("Bearer", accessToken, expiresIn);
    }
}

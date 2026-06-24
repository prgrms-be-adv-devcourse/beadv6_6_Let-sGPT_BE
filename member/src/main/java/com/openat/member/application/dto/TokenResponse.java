package com.openat.member.application.dto;

/**
 * 로그인/리프레시 응답 포맷.
 * tokenType은 OAuth2 표준 용어인 "token_type"(예: "Bearer")이다 — "grant_type"은 요청 시 인증 방식을
 * 가리키는 별개의 개념(예: "password", "refresh_token")이라 응답 필드명으로는 쓰지 않는다.
 */
public record TokenResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long expiresIn
) {
    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn) {
        return new TokenResponse("Bearer", accessToken, refreshToken, expiresIn);
    }
}

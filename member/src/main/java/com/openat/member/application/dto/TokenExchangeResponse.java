package com.openat.member.application.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 8693 §2.2 token exchange 응답.
 * JSON 필드명은 RFC 8693 표준 명칭을 따른다.
 */
public record TokenExchangeResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("issued_token_type") String issuedTokenType,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        String scope
) {

    public static TokenExchangeResponse of(String accessToken, long expiresIn, String scope) {
        return new TokenExchangeResponse(
                accessToken,
                TokenExchangeRequest.TOKEN_TYPE_JWT,
                "Bearer",
                expiresIn,
                scope
        );
    }
}

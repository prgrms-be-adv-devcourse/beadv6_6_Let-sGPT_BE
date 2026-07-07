package com.openat.member.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 {@code jwt.*} 값을 바인딩한다.
 * private-key/public-key는 PEM이 아니라 DER을 Base64로 인코딩한 한 줄 문자열이다
 * (.env.example 주석의 openssl 명령으로 생성).
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String keyId,
        String privateKey,
        String publicKey,
        long accessTokenExpireSeconds,
        long refreshTokenExpireSeconds,
        /** 토큰 발급자 URI (iss 클레임). 예: http://localhost:9100 */
        String issuer,
        /** RFC 8693 scoped 토큰 만료 초. 기본값 120초 */
        long scopedTokenExpireSeconds
) {
}
